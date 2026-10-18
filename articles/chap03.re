= ページとスロットによるデータ配置の改善

== 第2章までの課題：更新のたびにファイル全体を書き直す

第2章では、プログラムを終了してもデータが消えないように、レコードをテキストファイルへ保存する仕組みを作りました。

一方で、insert, update, delete によりレコードを1件でも変更したら「ファイル全体を書き直している」という点が非効率です。1万件のレコードがある状態で1件を更新すると、変更のなかった9999件も含めてすべてを書き出すことになります。書き込む量はレコードの総数に比例して増えていくため、データが増えるほど1回の更新が重くなっていきます。

これは保存の単位がファイル全体になっているためです。データベースが扱うデータは、ファイルの先頭から末尾まで1本のテキストとして並んでいるだけなので、途中の1件だけを差し替える手段がありません。前章の末尾で確認したように、テキストの「行」は可変長であり、1件の更新がファイル内の後続データの位置すべてをずらしてしまうことが根本的な原因でした。

== 本章の方針：ページ単位での読み書き

この課題を解決するには、保存の単位をファイル全体よりも小さくし、変更したい部分だけを読み書きできるようにする必要があります。

そこで本章では@<b>{ページ}を導入し、更新のたびにファイル全体を書き直すのではなく、@<b>{変更のあった部分だけを読み書きする}実装へと作り替えます。具体的には、次の順序で進めます。

 1. @<b>{ページ単位の導入}: ファイルを一定サイズの固定長の単位に区切り、目的の場所へ直接アクセスできるようにします（DiskManager）。
 2. @<b>{ページ内の設計}: 1ページの中を「ラインポインタ」と「レコード」で管理し、ページ番号とスロット番号でレコードの位置（TupleId）を表現できるようにします（Page・Record）。
 3. @<b>{空き容量マップの導入}: 新しいレコードの挿入先ページを、ファイル全体の走査なしに決められるようにします（FreeSpaceMap）。
 4. @<b>{CRUDの実装と計測}: ページ単位の読み書きを用いて、必要な分だけのディスクI/OでCRUD操作を完結させ、@<code>{time} コマンドで処理時間を確認します。

//noindent
この作り替えにより、レコードを1件変更するときにファイルへ書き込む量は、データの総数に関わらず「1ページ分」で一定になります。なお、本章で実装するページの構造は、PostgreSQLがヒープページに採用している仕組み（ヘッダ＋ラインポインタ＋ページ後方から詰める可変長データ）と同じ考え方に基づいています。実在するデータベースと同じ設計を、先人たちが解決した課題とともに学んでいきましょう。

== 固定長ページの導入（DiskManager）

ページとは、ファイルを一定の大きさで区切った固定長の単位のことです。本書では1ページを4096バイト（4KB）とし、ファイルの先頭から4096バイトずつ「ページ0」「ページ1」「ページ2」……と区切って管理します。

固定長であることの最大のメリットは、計算によって位置を即座に特定できることです。あるページのファイル先頭からのバイト位置（オフセット）は「ページ番号 @<m>{\times 4096}」で計算できます。そのため、Javaの @<code>{RandomAccessFile} の @<code>{seek()} メソッドを使えば、目的のページへ直接移動し、そのページだけを読み書きできるようになります。

こうしたディスクとの入出力を一手に引き受けるのが、DiskManagerクラスです。

//emlist[DiskManagerクラス：ページ単位の読み書き][java]{
public class DiskManager implements AutoCloseable {
  private final RandomAccessFile file;

  public DiskManager(String dbFilePath) throws IOException {
    Path path = Path.of(dbFilePath);
    if (path.getParent() != null) {
      Files.createDirectories(path.getParent());
    }
    this.file = new RandomAccessFile(path.toFile(), "rw");
  }

  // 指定したページ番号の4KBデータをディスクから読み出し、
  // Page オブジェクトとして返す
  public Page readPage(int pageNo) throws IOException {
    if (pageNo < 0) {
      throw new IllegalArgumentException(
          "Page number cannot be negative: " + pageNo);
    }
    byte[] buf = new byte[Page.PAGE_SIZE];
    // ページ番号 × 4096バイトの位置へシーク
    file.seek((long) pageNo * Page.PAGE_SIZE);
    file.readFully(buf);
    return new Page(buf);
  }

  // 指定したページ番号の位置へ、Page オブジェクトの4KBバイト配列を書き込む
  public void writePage(int pageNo, Page page) throws IOException {
    if (pageNo < 0) {
      throw new IllegalArgumentException(
          "Page number cannot be negative: " + pageNo);
    }
    if (page == null) {
      throw new IllegalArgumentException("Page cannot be null.");
    }
    file.seek((long) pageNo * Page.PAGE_SIZE);
    file.write(page.getData());
  }

  // 現在のファイルサイズから、保存されている総ページ数を計算して返す
  public int getPageCount() throws IOException {
    return (int) Math.ceil((double) file.length() / Page.PAGE_SIZE);
  }
  // close()（中略）
}
//}

//noindent
読み込みには @<code>{readFully()} を使っています。これは「指定バイト数を確実に読み終えるまで待つ」メソッドで、途中までしか読めなかった場合のバグを防ぎます。一方、書き込みは write() で4KBをそのまま上書きします。このように「ディスクとのやりとりをすべて1クラスに集める」ことで、データベースの他の部分は「ページ番号を渡せばページがもらえる」という単純なインターフェースだけで動けるようになります。

次は、その「ページの中身」をどのように設計するのかを見ていきましょう。

== レコードの内部表現（Record）

前節で作ったDiskManagerは、ページを4KBのバイト配列として読み書きする仕組みでした。そのため、ページの中に置けるデータは@<b>{バイト列}だけです。メモリ上のJavaのオブジェクトをそのまま置くことはできず、レコードをバイト列へ変換する仕組みが必要になります。変換という操作は、前章で解説した直列化（シリアライズ）と同じです。

ただし、前章の形式をそのまま使い回すことはできません。前章のカンマ区切りのテキストは、HashMapの中身を一括して書き出し、起動時に一括して読み戻すという用途に合わせた形式でした。1行の長さはまちまちで、レコードの境界はカンマと改行という区切り文字に依存しています（前章の末尾で確認したとおり、これが更新のたびの全件書き直しにつながっていました）。本章で扱うのはそれとは逆に、「ページの中の決まった位置へ、レコードを1件ずつ読み書きする」ことに使える形式です。そのためには、レコード1件のバイト数を書き込む前に確定できること、読み戻すときは「どこからどこまでが1件か」を区切り文字をたどらずに長さから判断できることが求められます。この条件を満たすため、本章ではシリアライズの出力をテキストからバイト列へ改めます。

本章では、レコードを key と value の文字列のペアとして表現するRecordクラスを導入します。

//emlist[Recordクラス][java]{
public record Record(String key, String value) {
  // メモリ上の Record オブジェクトを、
  // ディスクに保存可能なバイト配列（byte[]）に変換する
  public byte[] serialize() {
    byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
    byte[] valBytes = value.getBytes(StandardCharsets.UTF_8);

    byte[] bytes = new byte[2 + keyBytes.length + 2 + valBytes.length];
    ByteBuffer buffer = ByteBuffer.wrap(bytes);

    buffer.putShort((short) keyBytes.length);
    buffer.put(keyBytes);
    buffer.putShort((short) valBytes.length);
    buffer.put(valBytes);

    return bytes;
  }
  // deserialize()、getSerializedSize()（後述）
}
//}

//noindent
シリアライズの形式は次の通りです。長さが2バイトの符号なし整数（65535バイトまで）なので、キーと値はそれぞれ最長65535バイトの文字列を扱えます。

//emlist[Recordのシリアライズ形式（レングスプレフィクス）]{
バイト位置:      0        2                  2+K               2+K+2
              +---------+------------------+---------------+------------------+
              | keyLen  |       key        |    valLen     |      value       |
              +---------+------------------+---------------+------------------+
                 2B          K バイト          2B               V バイト

  例: key="Alice" (K=5), value="Beer" (V=4) のとき
      全長 = 2 + 5 + 2 + 4 = 15バイト
      +---------+------------+---------+------------+
      | 05      | Alice      | 04      | Beer       |
      +---------+------------+---------+------------+
//}

//noindent
書き出されたバイト列を見ると、@<b>{データ長をそのデータのすぐ手前に記録している}点が特徴です。これを@<b>{レングスプレフィクス}と呼びます。手前の長さを2バイト読み、その長さぶんだけ読む、という処理の繰り返しで1件のデータの範囲が確定するため、区切り文字の位置を探す必要がありません。前章のカンマと改行に頼った形式と違って、key や value にカンマや改行が含まれていても破損しません。また、長さの数値は「このレコードを格納するのに必要なバイト数」そのものなので、次節で扱う「ページ内のできごとからどのバイト数を確保するか」の計算にも、この長さ情報をそのまま使えます。

読み込み側では、書き込んだのと同じ順序でバイト列を解釈します。

//emlist[Recordクラス：デシリアライズ][java]{
public static Record deserialize(ByteBuffer buffer) {
  int keyLen = Short.toUnsignedInt(buffer.getShort());
  byte[] keyBytes = new byte[keyLen];
  buffer.get(keyBytes);
  String key = new String(keyBytes, StandardCharsets.UTF_8);

  int valLen = Short.toUnsignedInt(buffer.getShort());
  byte[] valBytes = new byte[valLen];
  buffer.get(valBytes);
  String value = new String(valBytes, StandardCharsets.UTF_8);

  return new Record(key, value);
}

// このレコードをシリアライズした際の総バイト数を返す
public int getSerializedSize() {
  byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
  byte[] valBytes = value.getBytes(StandardCharsets.UTF_8);
  return 2 + keyBytes.length + 2 + valBytes.length;
}
//}

//noindent
これで「1件のレコードをバイト列にする／バイト列から戻す」ことができるようになりました。ただし、これだけでは「レコードをファイルのどの位置へ書けば、あとから確実に読み戻せるのか」が決まりません。この位置の問題に取り組むのがページの設計です。

== ページの内部構造：ラインポインタとレコード

ページの中身を考えるとき、重要なのは「レコードを消したり新しいレコードを足したりしても、ページ内のどこに何があるのかを正しく把握できること」です。本書では、これを解決するために@<b>{ラインポインタ（Line Pointer）}という仕組みを使います。これはPostgreSQLのヒープページで使われている設計そのものです。

ページの中身を図にしておきます。図の数値は「3件のレコードを挿入し終えたページ」の例です。キー・値は、どれも key が1バイト・value が6バイト（例：`Alice`）で、レコード1件の実体は 2+1+2+6 = 11バイトです。

//emlist[ページの内部：ラインポインタ配列とレコード実体]{
 off 0
+-------------------------------+
| lower=16  | upper=4063        |   ヘッダ (4バイト)
+-------------------------------+
| LP[0]  4085  11  NORMAL       |   ラインポインタ
| LP[1]  4074  11  NORMAL       |   (1行=1スロット=4バイト)
| LP[2]  4063  11  NORMAL       |
+-------------------------------+   <- lower の境界 (16バイト)
|                               |
|          空き領域              |
|                               |
+-------------------------------+   <- upper の境界 (4063バイト)
| 2  3  2  Carla   (LP[2])      |
| 2  2  2  Betty   (LP[1])      |
| 2  1  2  Alice   (LP[0])      |
+-------------------------------+   <- ページの末尾 (オフセット4096)
//}

//noindent
LP の行は「スロット番号・レコード実体のオフセット・データ長（バイト数）・フラグ」を並べたものです。レコードの行は「長さ付き形式（keyLen・key・valLen・value）」の1件分に相当します。

最後に書かれたレコードがページ末尾側のいちばん深い位置に置かれ、LP[0]（いちばん新しいスロット）がそこを指します。レコード実体は末尾から先頭方向へ積み重なり、ラインポインタ配列は先頭から末尾方向へ伸びるため、2つが中央の空き領域で迫り合う構造です。空き領域が尽きたときがページ満杯のときです。以降、ページの構造を先頭から順に見ていきます。

==== ページヘッダ（4バイト）

ページの先頭4バイトには、2バイトずつの整数が2つ入っています。

 * @<b>{lower}：ページの先頭から何バイト目まで、ラインポインタ配列が使用中か
 * @<b>{upper}：ページの末尾から何バイト目に、最後のレコードが入っているか

ページの初期状態では、lowerは4（ヘッダの直後）、upperは4096（ページ末尾）です。

//emlist[Pageクラス：コンストラクタとヘッダの初期化][java]{
public Page() {
  this.data = new byte[PAGE_SIZE];
  this.buffer = ByteBuffer.wrap(data);
  setLower(HEADER_SIZE);
  setUpper(PAGE_SIZE);
}
//}

//noindent
ラインポインタ配列はページの先頭側から末尾に向かって成長し、レコードの実体はページ末尾側から先頭に向かって成長します。2つが中央の「空き領域」で迫り合う構造になっており、空き領域が尽きたときがページ満杯のときです。

==== ラインポインタ（4バイト／1スロット）

ラインポインタは、索引のように各レコードがページ内のどこにあるかを簡潔に示す「住所録」です。1本4バイトで、以下の3つの情報を1つの整数に埋め込んでいます。

//table[ラインポインタ1本の内訳（4バイト）][ラインポインタ1本の内訳（4バイト）]{
フィールド	ビット数	意味
-------------------------------------------------------------
オフセット	15ビット	レコード実体のあるページ内のバイト位置
フラグ	2ビット	使用状態（後述のLP_*）
データ長	15ビット	レコード実体のバイト数
//}

1本のラインポインタは、32ビット整数の中に3つの情報を詰め込んだ形をしています（LSB側がページオフセット）。

//emlist[ラインポインタ（4バイト）のビット配置]{
 bit   31            17          14        0
       +---------------+--+---------------+
       |  length (15b) |fl|  offset (15b) |
       +---------------+--+---------------+

 例: LP[0] = 0x00168FF5
     offset = 4085, flag = 1 (LP_NORMAL), length = 11
//}

//noindent
ビット演算で3つの値を1つの整数へ詰め込む実装が、PageクラスのsetLinePointer とその逆の読み出し群です。

//emlist[ラインポインタの読み書き][java]{
public void setLinePointer(int lpIndex, int offset, int flags, int length) {
  int lp = (offset & OFFSET_MASK)
             | ((flags & FLAGS_MASK) << 15)
             | ((length & LENGTH_MASK) << 17);
  buffer.putInt(HEADER_SIZE + lpIndex * LINE_POINTER_SIZE, lp);
}

public static int lpOffset(int lp) {
  return lp & OFFSET_MASK;
}

public static int lpFlags(int lp) {
  return (lp >> 15) & FLAGS_MASK;
}

public static int lpLength(int lp) {
  return (lp >> 17) & LENGTH_MASK;
}
//}

//noindent
@<code>{OFFSET_MASK}（15ビット）、@<code>{FLAGS_MASK}（2ビット）で必要な部分だけを取り出し、ビットシフト（@<code>{<< 15}、@<code>{<< 17}）で詰め込んでいます。このパッキングにより、たった4バイトで「どこに・どんな状態で・どれだけ」を表現できるのです。

フラグはスロットの使用状態を表し、次の3種類があります。

//table[ラインポインタのフラグ][ラインポインタのフラグ]{
フラグ	値	意味
-------------------------------------------------------------
@<code>{LP_UNUSED}	0	未使用スロット（新規データに再利用可能）
@<code>{LP_NORMAL}	1	通常の有効データ
@<code>{LP_DEAD}	3	削除済みデータ（まだ回収されていない）
//}

//noindent
なお「スロット」という言葉は、このラインポインタ配列の1要素を指します。第4章では、これをB-treeの検索結果（レコードの位置）としてそのまま使うことになります。位置情報を表すTupleIdクラスも、このタイミングで導入します。

//emlist[TupleIdクラス：レコードの物理的な位置][java]{
// データベース内におけるレコードの物理的な配置場所（アドレス）を表す識別子
// ページ番号（pageIndex）と、そのページ内のスロット番号（slotIndex）を保持する
public record TupleId(int pageIndex, int slotIndex) {
  @Override
  public String toString() {
    return "(Page: " + pageIndex + ", Slot: " + slotIndex + ")";
  }
}
//}

//noindent
「ページ番号とスロット番号」が分かればレコードを一意に指せる、というのがこの章を通じた最も重要な設計上の決定です。後の章（インデックス、実行計画）でも、レコードの位置はすべてTupleIdとして扱います。

== レコードの挿入：upperとlowerを動かす

準備が整ったので、ページへのレコード追加を見ていきます。PageクラスのinsertRecordメソッドは、次の流れで動きます。

 1. 未使用のスロット（@<code>{LP_UNUSED}）を探す。
 2. みつからなければ、新しいラインポインタを1本増やす。そのための空き領域が足りない、あるいは削除済み（@<code>{LP_DEAD}）スロットがある場合は、compactメソッドでページ内の詰め直し（コンパクション）を行う。
 3. それでも空きが足りなければ、@<code>{-1} を返して「このページには入らない」ことを伝える。
 4. upper をレコードのサイズぶん下げ、その位置へシリアライズしたレコードを書き込む。
 5. 新規スロットのときは lower をラインポインタ1本分（4バイト）進める。
 6. ラインポインタを @<code>{LP_NORMAL} で登録し、スロット番号を返す。

//emlist[Pageクラス：レコードの挿入][java]{
public int insertRecord(Record record) {
  if (record == null) {
    throw new IllegalArgumentException("Record cannot be null.");
  }
  byte[] recordBytes = record.serialize();
  int requiredSpace = recordBytes.length;

  // 未使用スロット（LP_UNUSED）を探索
  int targetLpIndex = findUnusedSlot();
  boolean needNewLp = (targetLpIndex == -1);
  int totalSpaceNeeded = requiredSpace + (needNewLp ? LINE_POINTER_SIZE : 0);

  // 空き容量が不足しているか、スロットが満杯でLP_DEADが存在する場合、
  // 遅延コンパクションを実行
  if ((getFreeSpace() < totalSpaceNeeded || needNewLp) && hasDeadTuples()) {
    compact();
    targetLpIndex = findUnusedSlot();
    needNewLp = (targetLpIndex == -1);
    totalSpaceNeeded = requiredSpace + (needNewLp ? LINE_POINTER_SIZE : 0);
  }

  if (getFreeSpace() < totalSpaceNeeded) {
    // 入りきらないときはここでエラーが返る
    return -1;
  }

  // 実データを upper の手前へ書き込み
  int newUpper = getUpper() - requiredSpace;
  setUpper(newUpper);

  buffer.position(newUpper);
  buffer.put(recordBytes);

  // 新規スロットの場合は lower を進める
  if (needNewLp) {
    targetLpIndex = getNumLinePointers();
    setLower(getLower() + LINE_POINTER_SIZE);
  }

  // ラインポインタを LP_NORMAL として記録
  setLinePointer(targetLpIndex, newUpper, LP_NORMAL, requiredSpace);
  return targetLpIndex;
}
//}

//noindent
処理の中核は、「upper を使ってレコードの置き場所を決める」という1行です。ページの末尾からレコードサイズぶんの領域を確保してそこへ書き込むため、既存のレコードの位置が一切動くことはありません。また、スロット番号（ラインポインタの番号）を返すことで、呼び出し元は新しく登録されたレコードのTupleIdをそのまま手に入れられます。

ここで注目したいのが、@<code>{LP_UNUSED} スロットの再利用と、@<code>{compact} メソッドによる遅延コンパクションです。これらは次のdeleteの解説で意味が分かります。

== レコードの削除：論理削除と回収（コンパクション）

レコードを削除するとき、ページの中身を実際に消したり、後ろのレコードを前に詰めたりはしません。その代わり、対象スロットのフラグを @<code>{LP_DEAD} に変える@<b>{だけ}で削除とみなします。これを@<b>{論理削除}と呼びます。

//emlist[Pageクラス：レコードの論理削除][java]{
public boolean deleteRecord(int lpIndex) {
  if (lpIndex < 0 || lpIndex >= getNumLinePointers()) {
    return false;
  }

  int lp = getLinePointer(lpIndex);
  if (lpFlags(lp) == LP_NORMAL) {
    setLinePointer(lpIndex, lpOffset(lp), LP_DEAD, lpLength(lp));
    return true;
  }
  return false;
}
//}

//noindent
なぜ、削除の瞬間に詰め直さないのでしょうか。理由は主に2つです。

 * 削除のたびに他のレコードを動かすと、ページ内の移動分の書き込みが発生し、I/Oが無意味に増える。また、ラインポインタの番号（スロット番号）が変わってしまうと、TupleIdを持っている他の仕組み（これから作るインデックス）の整合性が取れなくなる。
 * 「すぐに消す必要はないがスペースは再利用したい」という需要には、まとめて回収する方が効率が良い。

//noindent
回収の実務はcompactメソッドです。ページ内の @<code>{LP_NORMAL} レコードだけをページ末尾側へ隙間なく詰め直し、@<code>{LP_DEAD} スロットは @<code>{LP_UNUSED} へ戻します。これにより、レコード実体の領域が回収され、upper が回復します。この間、ラインポインタの番号は不変であるため、TupleIdとの対応関係は壊れません。

//emlist[compactメソッド：スロットの詰め直し（要約）][java]{
public void compact() {
  // LP_NORMALの有効データのみをページ末尾（Upper側）に隙間なく詰め直す
  // （実装の要点のみ。データの移動とラインポインタの更新が行われる）
  byte[] temp = new byte[PAGE_SIZE];

  // ページ先頭（ヘッダ＋ラインポインタ配列）はそのままコピー
  System.arraycopy(this.data, 0, temp, 0, getLower());

  int currentUpper = PAGE_SIZE;

  for (int i = 0; i < getNumLinePointers(); i++) {
    int lp = getLinePointer(i);
    int flags = lpFlags(lp);

    if (flags == LP_NORMAL) {
      // 有効データだけを末尾から積み直し、ラインポインタの位置情報を更新する
      ...
    } else if (flags == LP_DEAD) {
      // LP_DEAD のデータ領域は破棄し、スロットを LP_UNUSED にリセット
      ...
    }
  }
  // 詰め直した結果をページへ反映し、upper を更新する
}
//}

//noindent
この「削除はフラグを立てるだけ、回収は後でまとめて」という設計は、@<b>{遅延処理（Deferred Work）}というデータベースの実践的な考え方そのものです。なお、PostgreSQLではこの回収に相当する処理をVACUUMと呼びます。本書では、insertRecord の中で「必要になったときだけ」コンパクションが走る方式（空きスペースが足りなくなったときに回収する）を採用しています。

== レコードの更新：同じページでの削除と挿入、およびページをまたぐ再配置

レコードの更新は、deleteとinsertの組み合わせとして実現します。PageクラスのupdateRecordメソッドは、旧レコードを LP_DEAD に変えてから、新レコードの挿入を試み、新しいスロット番号を返します。

//emlist[Pageクラス：レコードの更新（ページ内での再配置）][java]{
public int updateRecord(int oldLpIndex, Record record) {
  if (record == null || oldLpIndex < 0 || oldLpIndex >= getNumLinePointers()) {
    return -1;
  }

  int oldLp = getLinePointer(oldLpIndex);
  if (lpFlags(oldLp) != LP_NORMAL) {
    return -1;
  }

  deleteRecord(oldLpIndex);
  return insertRecord(record);
}
//}

//noindent
更新前と更新後でレコードのサイズが変わる場合、同じ位置に上書きすることはできません（サイズが違うと以降のレコードの位置がずれてしまう）。そこで「旧データは LP_DEAD へ、新データは空き位置へ」をセットでおこなうためです。update直後は旧データがページ内に残りますが、値としては新データが使われ、旧データの領域は次のコンパクションのときに回収されます。

問題は、ページが満杯で新レコードが入らないときです。このときは、@<b>{別のページへレコードを再配置}する必要があります。この判断は、ページ単体ではできません。複数ページを統括する役割が必要です。その総称が次の節で説明するPageManagerです。

== 空き容量マップ（FreeSpaceMap）を導入する

新規レコードの挿入先ページを決めるには、「どのページに空きがあるか」を知る必要があります。毎回ディスクから全ページを読み込んで確認するのは、前章と同じく非効率的です。

そこで、各ページの空き容量をメモリ上で管理するFreeSpaceMap（FSM）を導入します。

//emlist[FreeSpaceMapクラス][java]{
// 新規レコード挿入時に、ディスクを全走査することなく
// 「どのページに収まるか」を高速に特定するために使用する
public class FreeSpaceMap {
  // インデックス = ページ番号, 値 = そのページの現在の空き容量（バイト）
  private final List<Integer> spaceMap;

  public void addPage(int freeSpace) {
    spaceMap.add(freeSpace);
  }

  // 既存ページの空き容量を更新する
  public void updatePage(int pageNo, int freeSpace) {
    if (pageNo >= 0 && pageNo < spaceMap.size()) {
      spaceMap.set(pageNo, freeSpace);
    }
  }

  // 指定した要求容量（requiredSpace）を格納可能な最初のページ番号を返す
  // 既存ページに収まらない場合は -1 を返す（呼び出し元は新規ページを追加する）
  public int getTargetPage(int requiredSpace) {
    for (int i = 0; i < spaceMap.size(); i++) {
      if (spaceMap.get(i) >= requiredSpace) {
        return i;
      }
    }
    return -1;
  }

  // 総ページ数
  public int getPageCount() {
    return spaceMap.size();
  }
}
//}

//noindent
FSMは「ページ番号 → 空きバイト数」の単純な対応表です。挿入時には、先頭から順に「要求サイズ以上の空き」があるページを探して返します（ファーストフィット）。新規レコードは最後に追加されたページの末尾近くに集まる（つまり末尾のページほど空いている）ことが多いため、この単純な方式でも実用上十分に機能します。

なお、FSMの空き容量には @<code>{LP_DEAD} の領域も含めて計算しています。

//emlist[Pageクラス：ページの「潜在的な」空き容量][java]{
// LP_DEAD による回収可能領域を含めた、
// ページ内の潜在的な総空き容量（バイト数）を返す
public int getTotalFreeSpace() {
  int free = getUpper() - getLower();
  for (int i = 0; i < getNumLinePointers(); i++) {
    int lp = getLinePointer(i);
    if (lpFlags(lp) == LP_DEAD) {
      free += lpLength(lp);
    }
  }
  return free;
}
//}

//noindent
なぜ、FSMをディスク上に保存しないのかには理由があります。FSMの内容はレコードの削除や更新ですぐに変わってしまう情報だからです。そこでFSMは「メモリ上にのみ持ち、データベースの起動時にいつでもすべてのページを走査して再構築できるもの」としています。これにより、FSMを消してもデータは失われず、起動時の構築さえ正しければシステムとしての安全性が保たれます。

//emlist[ページ管理とディスクの関係]{
+---------------------+
|    PageManager      |  <- ページたちの統括とCRUDの統合
+-----+------------+--+
      |            |
      v            v
+-----------+  +---------------+
|    FSM    |  |  DiskManager  |  <- ページ単位の読み書き
+-----------+  +---------------+
    ^                  |
    |                  v
    |            +--------------------------+
    |            | page0 | page1 | page2 ...|  <- ディスク上のページ (4KB)
    |            +--------------------------+
    |
    +-- 起動時に全ページを走査して FSM を再構築する
//}

== PageManager：ページたちの統括とCRUDの統合

ページ、レコード、FSM、ディスク管理という部品はそろいました。これらを統括し、「キーを与えられたときに正しくCRUDを実行する」役割を担うのがPageManagerクラスです。MiniDBクラス（第1章のCRUD処理）は、本章以降、HashMapの代わりにPageManagerへ処理を任せるようになります。

PageManagerが提供する操作を確認します。

//table[PageManagerの主要メソッド][PageManagerの主要メソッド]{
メソッド	役割
-------------------------------------------------------------
@<code>{insertRecord(key, value)}	FSMで挿入先ページを決めてレコードを登録する
@<code>{findRecord(key)}	全ページを走査して一致する最初のレコードを返す
@<code>{findAllRecords()}	全ページを走査し、有効なレコードを返す
@<code>{updateRecordByKey(key, value)}	更新できなければFSMで別ページへ再配置
@<code>{deleteRecordByKey(key)}	対象レコードをLP_DEADにして書き戻す
//}

//noindent
いくつかの実装の要点を取り上げます。

==== 挿入

@<code>{insertRecord} は、FSMの @<code>{getTargetPage()} で挿入先ページを決め、そのページを読み込んで @<code>{Page.insertRecord()} を呼びます。既存ページに入らない場合（FSMが @<code>{-1} を返した、または挿入後にフルになった場合）は、新規ページを作成してそこへ挿入します。

//emlist[PageManagerクラス：レコードの挿入（要約）][java]{
public TupleId insertRecord(String key, String value) throws IOException {
  Record record = new Record(key, value);
  int requiredSpace = Page.calculateRequiredSpace(record);

  if (requiredSpace > Page.MAX_RECORD_SIZE) {
    throw new IllegalArgumentException(
        "Record is too large. Max allowed: " + Page.MAX_RECORD_SIZE + " bytes.");
  }

  int targetPageNo = fsm.getTargetPage(requiredSpace);
  if (targetPageNo != -1) {
    Page page = readPage(targetPageNo);
    int slot = page.insertRecord(record);
    if (slot != -1) {
      writePage(targetPageNo, page);
      return new TupleId(targetPageNo, slot);
    }
    // 実際の空き状況とFSMがズレている可能性があるため、FSMを最新化して再試行する
    fsm.updatePage(targetPageNo, page.getTotalFreeSpace());
  }

  // 新規ページを作成して挿入
  targetPageNo = getPageCount();
  Page page = new Page();
  int slot = page.insertRecord(record);
  writePage(targetPageNo, page);
  return new TupleId(targetPageNo, slot);
}
//}

//noindent
新規ページの作成は、「既存ページの数（getPageCount()）を新ページ番号とする配置」で行われます。ファイルの末尾に新しい4KBが追記されるため、ページ番号は位置情報そのものとして機能しています。

==== 更新：ページをまたぐ再配置と安全な取り消し

@<code>{updateRecordByKey} は、対象レコードのあるページ内で @<code>{updateRecord()} を試みます。ページに空きがあって新しいレコードが同じページに収まれば、そのページを書き戻すだけで完了です。収まらないときは、「FSMで空きのあるページを改めて探し、新ページへ挿入 → 旧ページのレコードを LP_DEAD へ → 書き戻す」という手順でページをまたぐ再配置をします。

この手順の途中でディスクI/Oエラーが発生すると、データベースは「旧ページが古い値、新ページにも同じレコード」という矛盾した状態を作ってしまいます。そこで実装では、新ページへの挿入の後、旧ページの書き込みに失敗した場合には新ページのレコードを削除して書き戻す、という@<b>{取り消し処理}を組み込んでいます。小さな実装でも、途中で失敗したときに「なかったこと」にできることを意識して作ることが、データベース自身を守る第一歩です。

==== 起動時のFSM構築

FSMはメモリ上にしか存在しないため、起動時にディスクの全ページを走査して再構築します。

//emlist[PageManagerクラス：起動時のFSM構築][java]{
private void buildFSM() throws IOException {
  int numPages = diskManager.getPageCount();
  for (int i = 0; i < numPages; i++) {
    Page page = diskManager.readPage(i);
    fsm.addPage(page.getTotalFreeSpace());
  }
}
//}

//noindent
データ量が増えると起動に時間がかかる可能性がありますが、「正しく空き容量がわかること」を最優先にした実装です。（この走査が重いことは、次章でB-Treeを導入するときの課題として再び登場します。）

==== REPLへの統合とtimeコマンド

MiniDBクラスのstartメソッドの見た目は第1章とほぼ同じですが、CRUDの実処理が @<code>{pageManager.findRecord()} などの呼び出しに置き換わっています（実際のディスクI/Oを伴うため、各メソッドは @<code>{IOException} を処理するようになっています）。

また、本章からは@<code>{time}コマンドが利用できます。次のコマンドで計測の表示を切り替えられるようになっています。

//cmd{
minidb > time
Timing is on.
minidb > select 10
...
(Executed in 2.935 ms)
minidb > time
Timing is off.
//}

//noindent
実装は単純で、コマンド実行の直前と直後で @<code>{System.nanoTime()} を測り、その差をミリ秒で表示しているだけです。パフォーマンスの変化は、次節の計測で確認します。

== 処理時間の計測とパフォーマンス評価

insert, update, delete のたびにレコード全体をファイルに書き出していた前章と、ページ管理を導入した本章の処理時間を、timeコマンドを使って比較してみましょう。

//table[performance_comparison][実行時間の比較（1万件のデータが存在する状態）]{
コマンド	前章（全件書き直し）	本章（ページ管理）
-------------------------------------------------------------
@<code>{select 5000}	0.315 ms	9.468 ms
@<code>{insert 10001 user10001}	16.114 ms	4.622 ms
@<code>{update 5000 test}	8.025 ms	3.751 ms
@<code>{delete 5000}	9.833 ms	2.378 ms
//}

//noindent
※実行環境やデータの配置により結果は変わります。数値はあくまでも実装の傾向を示す一例です。

前章と比較し、insert, update, delete の回にかかる時間が短くなっていることが確認できます。ファイル全体ではなく、1ページ（4KB）の読み書きで済むようになった効果が明確に現れています。

一方で、@<code>{select} の処理時間は大幅に増加しています。これは、前章までは「起動時にすべてのデータをメモリに載せていた」のに対し、本章からは「毎回ディスクからページを読み込んで探すようになった」ためです。ディスクアクセスが毎回発生するようになったことが、検索が遅くなった最大の理由です。

重要なのは、この性能差が@<b>{データの件数が増えるとさらに拡大する}点です。selectは、キーが一致するまで全ページを読み続ける線形探索のため、データが2倍になれば検索も約2倍遅くなります。この課題を解決するのが次章です。

== まとめと次章への課題

本章では、データベースのデータ配置を次のように改善しました。

 * ファイルを4096バイトの@<b>{ページ}という固定長の単位に区切り、ページの位置を「ページ番号 @<m>{\times 4096}」で即座に計算可能とした。
 * ページを@<b>{ヘッダ（lower/upper）}、@<b>{ラインポインタ配列}、@<b>{レコード領域}の3部に分け、レコードの位置を@<b>{ページ番号＋スロット番号（TupleId）}で認識できるようにした。
 * レコードの削除は@<b>{論理削除（LP_DEAD）}とし、領域の回収は@<b>{コンパクション}でまとめて行う遅延処理とした。
 * 挿入先ページの決定を、ディスク走査なしに済ませるための@<b>{空き容量マップ（FSM）}を導入した。

一方で、検索（select）には致命的な課題が残っています。キーが一致するまで全ページを読み続ける@<b>{線形探索（Linear Search）}を採用しており、レコード件数が1万件、100万件と増えるごとに、1件を探すために読み込むディスクI/O回数が激増してしまい、実用的な速度ではなくなってしまいます。

次章では、この検索速度の問題を根本から解決するために、データベースの心臓部とも言える@<b>{B-Tree（B木）}インデックスを導入します。ファイルの先頭から順に探すのではなく、「探しているキーがファイルのどこ（どのページとスロット）にあるのか」を一足飛びに知る仕組みを組み込み、再び圧倒的な検索スピードを取り戻します。
