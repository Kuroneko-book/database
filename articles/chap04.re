= B-Treeによる検索の高速化

== 第3章までの課題：線形探索の限界

第3章では、データを「ページ」という固定長のブロック単位で管理するようにデータベースを拡張しました。これにより、ディスクとの細かな入出力（I/O）が減り、書き込みの効率は大きく改善されました。

しかし、検索の仕組みには大きな課題が残っています。特定のレコードを探す（@<code>{select}）処理や、新しいレコードを追加する前の重複チェック、更新・削除対象のレコードがあるかどうかの確認は、すべて@<b>{ファイルの先頭から最後まで、すべてのページを順番に読み込んで調べている}という点です。

このような検索方法を@<b>{線形探索（Linear Search）}と呼びます。第3章のfindRecordは、すべてのページを読み込み、各ページのすべてのスロットのキーを確認してから、初めて「そのキーはデータベースに存在するかどうか」を判断します。線形探索の処理時間はデータの件数 N に比例する O(N) となります。データが数十件程度であれば処理時間はごくわずかですが、数万件、数百万件と増えていくと、たった1件のデータを検索・更新するだけでも毎回膨大なディスク読み込み（フルスキャン）が発生し、実用的な速度ではなくなってしまいます。

== 本章の方針：インデックスによる直接アクセス

この「毎回すべてのデータを調べる」という線形探索の課題を解決するため、本章では検索に特化したデータ構造（インデックス）を導入し、目的のデータが「ディスク上のどこにあるか」を一瞬で特定できるようにします。

具体的には、以下の順序で実装を進めます。

 1. @<b>{B-Tree（B木）の導入と設計}: 検索キーと物理的な位置（TupleId）を紐づけるデータ構造として、ページ管理と相性の良い「B-Tree」を採用し、ノードの構造を定義します。
 2. @<b>{B-Treeアルゴリズムの実装}: 木のバランスを常に保つため、検索処理に加え、ノードの分割（Split）や統合（Merge）を伴う挿入・削除アルゴリズムを実装します。
 3. @<b>{起動時のインデックス構築}: データベース起動時にファイルを1回だけ走査し、メモリ上にB-Treeを再構築します。
 4. @<b>{CRUD操作の直接アクセス化}: これまでループで全件走査していたCRUD処理を、B-Treeを用いたピンポイントな直接アクセスへと書き換え、どれくらい高速化したかを計測します。

//noindent
このステップを踏むことで、データ量が1万件、100万件と増えても、常に安定した速度で読み書きができる本格的なストレージエンジンが完成します。次節からはまず、「なぜ専用のデータ構造が必要になるのか」という基本的な考え方から見ていきましょう。

== インデックス：レコードの「住所」を持つデータ構造

特定のデータへ直接アクセス（ランダムアクセス）するためには、データ本体のファイルとは別に、「探しているデータがファイルの@<b>{どこ}にあるのか」をあらかじめ整理したデータ構造（対応表）を導入する必要があります。

100万件のデータから特定のIDを探すために、巨大なデータ本体を先頭から読み込むのは非常に非効率です。キーと場所のペアだけを抽出したコンパクトな対応表を用意しておけば、本体を探索するコストを劇的に下げることができます。現実世界で例えるなら、分厚い専門書を1ページ目から順番に読むのではなく、巻末の「索引」を見てページ番号を調べ、直接そのページを開くのと同じアプローチです。

プログラム上では、以下のような@<b>{「検索キー」と「データの物理的な場所」のペア}を管理する対応表を作成してこれを実現します。

//cmd{
[ 検索キー ]    | [ データの物理的な場所 ]
"10"            | (Page: 2,  Slot: 4)
"15"            | (Page: 8,  Slot: 1)
"8765"          | (Page: 120, Slot: 3)
...
//}

//noindent
ユーザーが「キーが"8765"のデータを検索したい」と要求した場合、データベースは以下のように動きます。

 1. 巨大なデータ本体を読む前に、まずはこのコンパクトな対応表を探索し、"8765"を見つけ出す
 2. そこに書かれている物理的な場所（ページ120、スロット3）をTupleIdとして取得する
 3. データ本体のファイルから、@<b>{ページ120を1枚だけディスクから読み込み}、インデックス経由で該当スロットのデータを取得する

次節からは、この仕組みをJavaのプログラムとして具体的にどのように実装していくのかを見ていきます。

== B-Treeの採用：TupleIdを値としたB-Tree

プログラム上でこの仕組みを実現するために、まずは物理的な場所をTupleIdクラスとして表現します。これは第3章で導入した「レコードの位置を表す識別子」そのものです。

//emlist[TupleIdクラス（位置情報）][java]{
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
そして、検索キー（文字列）とTupleIdのペアを管理するデータ構造として、配列や二分探索木ではなく@<b>{B-Tree（B木）}を採用します。
（※実際の商用データベースでは範囲検索に最適化された派生型「B+Tree」が主流ですが、本章ではその基礎となるB-Treeを実装します。また、正式な「インデックス」という概念（任意のカラムへ張る仕組み）については第6章以降で扱います。）

データ構造として配列やリストを使用すると、途中にデータを追加・削除するたびに要素をシフトさせる必要があり非効率です。また「二分探索木」は、1つのノードに1つのキーしか持たないため、データが増えると木が「縦に深く」成長してしまい、ディスクに保存した場合にページ読み込み（I/O）回数が膨大になります。

対してB-Treeには、第3章で学んだ@<b>{「ページ（ブロック）」との相性が良い}という決定的な強みがあります。具体的には以下の理由が挙げられます。

 * @<b>{1つのノードに複数のキーを持てる（マルチウェイ）:} B-Treeは1つのノードに数十〜数百個のキーを配列として格納します。これを1ページのサイズ（4KB等）に合わせることで、1回のディスク読み込みで大量のキーを一気にメモリへ読み込めます。
 * @<b>{階層が極めて浅い（横に広い）:} 1ノードに100個のキー（101本の枝）を持てば、高さ3階層で100万件のデータを表現できます。わずか最大3回のディスクI/Oで目的のデータに到達できます。
 * @<b>{常にバランスを保つ（平衡木）:} データの増減に合わせて自動で木を再構成するため、常に一定の速度（O(log N)）が保証されます。

== B-Treeの全体構造

B-Treeは、ノード（node）と呼ばれるいくつかの要素で構成されます。一番上にあるのが「ルートノード（根）」、中間が「内部ノード」、一番下が「リーフノード（葉）」です。各ノード内のキーは必ず昇順にソートされており、左右どちらのキーよりも大きいかといった大小比較を行って進むべき枝を絞り込みながら末端へと到達するのが、B-Treeによる検索メカニズムです。

//emlist[B-Treeの全体構造（高さ3の例）]{
                     [ 20 | 40 ]                <- ルート：キー2個
                    /     |      \
        [ 5 | 10 | 15 ] [ 20 | 30 | 35 ] [ 40 | 45 | 50 ]   <- 内部ノード
        /  ||  \        /  ||  \        /  ||  \
   (1,4)(2,4)(3,4)(4,4) (5,4)(6,4)(7,4)(8,4) (9,4)(10,4)(11,4)(12,4)

   リーフノードに書かれた (1,4) などは TupleId（ページ番号, スロット番号）。
   内部ノード1個（キー3個）の下に、リーフノード4個がぶら下がる。
//}

//noindent
図の各リーフノードの要素は、レコードの値そのものではなく、TupleId（どのページのどのスロットにレコードがあるか）を指しています。この点が、本章で構築するデータベースにおけるB-Treeの役割（インデックス）です。

== B-Treeの実装

B-Treeをデータベースのデータ構造として機能させるには、検索だけでなく、更新操作に合わせて木構造のバランスを維持する仕組みが必要です。以降では、ノードの構造から順に、「検索」「挿入と分割」「削除と補充・統合」の各操作がどのような意図で実装されているのかを解説します。

==== ノードの表現と最小次数

ノード（Node）の実装にあたっては、キーとTupleIdの配列を持たせます。さらに、「最小次数（minimum degree @<code>{t}）」と呼ばれるパラメータを導入し、ルート以外のノードが持てる最大キー数を @<code>{2*t - 1}、最小キー数を @<code>{t - 1} と定義します。本書の実装では、デフォルトの最小次数は @<code>{t=10} です。

//emlist[BTreeクラス：ノード構成][java]{
public final class BTree {
  static final int DEFAULT_MINIMUM_DEGREE = 10;

  // B-tree の1ノード。keys は常に昇順ソート済み。
  static final class Node {
    // ソート済みのキーと、それと並行した形で配置されたレコード位置
    final String[] keys;
    final TupleId[] values;

    // 子ノード。リーフノードでは参照しない
    final Node[] children;

    // keys / values のうち実際に使用している要素数
    int keyCount;

    // リーフノード（子を持たない）かどうか
    boolean leaf;

    Node(int minimumDegree, boolean leaf) {
      keys = new String[2 * minimumDegree - 1];
      values = new TupleId[2 * minimumDegree - 1];
      children = new Node[2 * minimumDegree];
      this.leaf = leaf;
    }
  }
  // 以下、検索・挿入・削除の実装
}
//}

//noindent
ノード内のキーが @<code>{k} 個あるとき、区切る枝の数は必ず @<code>{k + 1} 本になるため、ポインタ配列 @<code>{children} の最大サイズはキーの最大数に1を足した @<code>{2 * t} と定義されています。

なぜ最大キー数を @<code>{2*t - 1} とするという中途半端な数式にするのでしょうか。それは、ノードが満杯になって「2つに分割（Split）」される際の要件を美しく満たすためです。
満杯のノード（@<code>{2*t - 1}個のキー）を左右に分割し、真ん中の1つのキーを親に渡すと、残された左右のノードのキー数はどちらもピッタリ「@<code>{t - 1}」になります。分割直後でもノードが無駄なく最小基準を満たし、木全体のバランス（50%以上の使用率）を維持できるように計算されているのです。

==== ノード内の二分探索（lowerBound）

B-Treeの検索で最も重要な仕掛けが、ノード内の@<b>{二分探索}です。ノードのキーが昇順ソートされているおかげで、「探しているキーがどの枝（子）の向こうにあるか」を1回の当たりで見極められます。

//emlist[lowerBound：key以上となる最初の位置を二分探索で求める][java]{
// ソート済み keys の中で、key 以上となる最初のインデックスを二分探索で求める
private int lowerBound(Node node, String key) {
  int low = 0;
  int high = node.keyCount;
  while (low < high) {
    int middle = (low + high) >>> 1;
    if (node.keys[middle].compareTo(key) < 0) low = middle + 1;
    else high = middle;
  }
  return low;
}
//}

//noindent
探したいキーが「どこで区切られるか」を log(要素数) で見つけるための関数です。検索・挿入・削除のすべてがこの関数を利用して次のアクセス場所を決めます。

==== 検索処理（Search）

目的のレコードを探す際は、ルートノードから開始し、各ノードでlowerBoundを用いて探しているキーの範囲を見つけ出し、該当する子ノードへと再帰的に下っていくアプローチをとります。

//emlist[searchメソッド][java]{
// 指定したキーに対応する TupleId を返す。存在しない場合は null を返す
public TupleId search(String key) {
  Objects.requireNonNull(key, "key");
  return search(root, key);
}

private TupleId search(Node node, String key) {
  int index = lowerBound(node, key);

  // キーがこのノード内に見つかった場合、対応する値を返す
  if (index < node.keyCount && key.equals(node.keys[index])) {
    return node.values[index];
  }
  // リーフまで到達した場合は存在しない。そうでなければ該当する子を探索する
  return node.leaf ? null : search(node.children[index], key);
}
//}

//noindent
実装そのものはシンプルです。動作イメージもつかみやすく、木構造の検索の基本形です。

実際のデータベースでは、このノードをディスク上の1ページに配置します（ノードが4KBなど1ページにちょうど収まるように、キー数＝最小次数を調整します）。本書の実装では、簡潔さを優先してインデックスをメモリ上に構築しますが、「1ノード = 1ページ」という設計はディスクの読み書きとの相性が良く、インデックスそのものをディスクへ永続化する発展への出発点になっています。

==== 挿入とノードの分割（Split）

データを挿入する際、対象のノードが最大キー数に達して満杯だった場合、そのままでは新しいデータを追加できません。そこで、ノードを2つに分割（Split）し、真ん中のキーを親ノードに押し上げます。
これは、ノードの容量上限を超えないようにしつつ、「下から上へ」と木を成長させるためです。上へ持ち上がるように成長することで、すべての葉ノードまでの深さが常に均一に保たれ、バランスの崩れを防ぐことができます。
最小次数 @<code>{t=2}（最大キー数3）の満杯ノード @<code>{[10 | 20 | 30]} に分割が発生する様子を示します。

//embed[|latex|]{
\Needspace{18\Cvs}
//}

//emlist[ノードの分割処理（t=2、最大キー数3の例）]{
  分割前：満杯ノード
  +--------------------+
  |  [ 10 | 20 | 30 ]  |
  +--------------------+
              |
              v   中間キー 20 を親へ昇格
         +-----------+
         |  [ 20 ]   |
         +-----+-----+
             /      \
  +-----------+       +-----------+
  |  [ 10 ]   |       |  [ 30 ]   |
  +-----------+       +-----------+

  分割後：木の高さが1つ増え、左右は t-1 = 1 個（最小キー数をちょうど満たす）
//}

//noindent
実際のプログラムでは、「配列要素の右シフト（隙間づくり）」と「データの移動」によってこの分割処理を実現します。

//emlist[splitChildメソッド][java]{
// parent.children[index] が満杯（2t - 1 個）のとき、
// 中間キーを親へ昇格させて左右に分割する
private void splitChild(Node parent, int index) {
  Node full = parent.children[index];
  Node right = new Node(minimumDegree, full.leaf);

  // 中間キー（minimumDegree - 1 番目）を取り出しておく（親へ昇格させる）
  String middleKey = full.keys[minimumDegree - 1];
  TupleId middleValue = full.values[minimumDegree - 1];

  // 右側に残す要素（t - 1 個）を新しいノードへ移す
  int rightKeys = minimumDegree - 1;
  System.arraycopy(full.keys, minimumDegree, right.keys, 0, rightKeys);
  System.arraycopy(full.values, minimumDegree, right.values, 0, rightKeys);

  // 内部ノードなら子も右へ譲る。full 側の配列の後半はクリアしておく
  if (!full.leaf) {
    System.arraycopy(full.children, minimumDegree, right.children,
                     0, minimumDegree);
    for (int i = minimumDegree; i < full.children.length; i++) {
      full.children[i] = null;
    }
  }

  right.keyCount = rightKeys;
  full.keyCount = minimumDegree - 1;

  // 親の子配列に1つ分の空きを作り、昇格した中間キーを挟み込む
  System.arraycopy(
      parent.children, index + 1, parent.children, index + 2,
      parent.keyCount - index);
  parent.children[index + 1] = right;
  System.arraycopy(parent.keys, index, parent.keys, index + 1,
                   parent.keyCount - index);
  System.arraycopy(parent.values, index, parent.values, index + 1,
                   parent.values.length - index - 1);
  parent.keys[index] = middleKey;
  parent.values[index] = middleValue;
  parent.keyCount++;
}
//}

//noindent
挿入処理の面白い所は、@<b>{木が下向きに降りながら分割を処理する}という部分です。本書の実装では、降下中に満杯の子に遭遇したら、その場で分割（先行分割）を済ませてから下りていきます。これにより、戻りながら分割をする必要がなくなり、実装が簡潔になります。

//emlist[insertメソッド：先行分割をしながらリーフへ向かう][java]{
// キーと値を登録する。既にキーが存在する場合は値を差し替える（upsert）
public void insert(String key, TupleId value) {
  Objects.requireNonNull(key, "key");
  Objects.requireNonNull(value, "value");

  // ルートが満杯なら、木の高さが1つ増えるように分割する
  if (root.keyCount == root.keys.length) {
    Node oldRoot = root;
    root = new Node(minimumDegree, false);
    root.children[0] = oldRoot;
    splitChild(root, 0);
  }

  Node existing = root;
  while (true) {
    int index = lowerBound(existing, key);

    // キーが既に存在するなら値のみを差し替えて終了
    if (index < existing.keyCount && key.equals(existing.keys[index])) {
      existing.values[index] = value;
      return;
    }
    // リーフに到達したら、あらかじめ確保済みの余裕へそのまま挿入する
    if (existing.leaf) {
      insertAt(existing, index, key, value);
      return;
    }
    // 内部ノードの場合、降下先の子が満杯なら分割して中間キーをここに昇格させる
    Node child = existing.children[index];
    if (child.keyCount == child.keys.length) {
      splitChild(existing, index);

      // 分割で昇格したキーと比較し、挿入位置が右半分なら子の参照を1つ進める
      int comparison = key.compareTo(existing.keys[index]);
      if (comparison == 0) {
        existing.values[index] = value;
        return;
      }
      if (comparison > 0) index++;
    }
    existing = existing.children[index];
  }
}
//}

//noindent
@<code>{insertAt} は、決定された場所から後続の要素を右へ1つずつシフトしてから新しい要素を挿入するメソッド（配列の右シフト）です。ノード内の要素数は高々 @<code>{2*t - 1} なので、シフトのコストが実行速度の問題にならない程度に収まります。

==== 削除処理におけるバランスの維持（Borrow と Merge）

データを削除した結果、キーの数が最小基準（@<code>{t - 1}）を下回った場合（アンダーフロー）、隣接ノードからキーを移動させる「補充（Borrow）」、または隣接ノードと1つにまとめる「統合（Merge）」を行います。
これはB-Treeの厳格な最小キー数のルールを守るためです。この調整を行わないと、キーの少ないスカスカなノードが大量に発生してしまい、結果的に木が深くなって検索パフォーマンスが悪化してしまいます。

本書の実装では、削除を始める前に「これから降りていく子のキー数が @<code>{t - 1} なら、事前に余裕を持たせてから降りる」という方針で作られています。こうすることで、降りた先のノードは最低でも @<code>{t - 1 + 1 = t} 個のキーを持ち、削除の途中でノードの最小キー数を下回ることはありません。

//emlist[deleteメソッド：降りる前にバランスを整える][java]{
private void delete(Node node, String key) {
  int index = lowerBound(node, key);

  // キーがこのノード内にある場合
  if (index < node.keyCount && key.equals(node.keys[index])) {
    if (node.leaf) {
      removeKey(node, index);
    } else {
      deleteInternalKey(node, index);
    }
    return;
  }
  // リーフに到達したなら、そのキーは木に存在しない
  if (node.leaf) {
    return;
  }

  // 降下先の子を決める（下限は minimumDegree - 1 個）
  Node child = node.children[index];

  // 降下先の子が下限（t - 1 個）なら、降下前に余裕を持たせておく
  if (child.keyCount == minimumDegree - 1) {
    // 左の兄弟に余裕があればキーを借用する
    if (index > 0 && node.children[index - 1].keyCount >= minimumDegree) {
      borrowFromPrevious(node, index);
    } else if (index < node.keyCount
               && node.children[index + 1].keyCount >= minimumDegree) {
      borrowFromNext(node, index);
    } else if (index < node.keyCount) {
      mergeChildren(node, index);
    } else {
      // 最右の子だった場合は、左の兄弟と統合する
      mergeChildren(node, index - 1);
      index--;
    }
  }
  delete(node.children[index], key);
}
//}

//noindent
補充（Borrow）では、大小関係を保つために直接横に移動させるのではなく、「親のキーを自分に降ろし、隣接ノードのキーを親に引き上げる」という@<b>{回転}動作を行います。

//emlist[バランス維持の処理（Borrow／右回転）]{
  削除で左の子が下限（t-1 = 1 個）に落ちたとき
             親
      +-----------------+
      |  [ 20 | 40 ]    |
      +--------+--------+
        /             \
+-----------------+    +-----------+
|  [ 10 | 15 ]    |    |  [ 25 ]   |
+-----------------+    +-----------+
   余裕あり                 下限

  ① 左の子の 15 を親へ引き上げる
  ② 親の 20 を右の子へ降ろす（右回転）
  結果：  左 [ 10 ]   親 [ 15 | 40 ]   右 [ 20 | 25 ]
//}

//emlist[borrowFromPreviousメソッド：左の兄弟から1要素分の余裕を借りる][java]{
// 子が下限のとき、左兄弟の最大キーを経由して1要素分の余裕を貰う（右回転）
private void borrowFromPrevious(Node parent, int index) {
  Node child = parent.children[index];
  Node sibling = parent.children[index - 1];

  // child の要素を1つ右へずらして空きを作る
  System.arraycopy(child.keys, 0, child.keys, 1, child.keyCount);
  System.arraycopy(child.values, 0, child.values, 1, child.keyCount);

  // 親の区切りキーを子の先頭へ降ろす
  child.keys[0] = parent.keys[index - 1];
  child.values[0] = parent.values[index - 1];

  // 左兄弟の最大キーを親へ昇格させる
  parent.keys[index - 1] = sibling.keys[sibling.keyCount - 1];
  parent.values[index - 1] = sibling.values[sibling.keyCount - 1];

  sibling.keyCount--;
  child.keyCount++;
}
//}

//noindent
右の兄弟から借りる borrowFromNext も方向が逆の同じ考え方です。

//noindent
一方、隣接ノードに余裕がない場合は、親の境界キーを降ろし、対象ノードと隣接ノードを1つにまとめる統合（Merge）を行います。

//emlist[mergeChildrenメソッド（要約）][java]{
// 下限の2つの子を、間の親キーとともに1つのノードへ統合する
private void mergeChildren(Node parent, int index) {
  Node left = parent.children[index];
  Node right = parent.children[index + 1];

  // 親の区切りキーを左ノードの末尾に降ろし、右ノードの中身を続けて連結する
  left.keys[left.keyCount] = parent.keys[index];
  left.values[left.keyCount] = parent.values[index];
  System.arraycopy(right.keys, 0,
                   left.keys, left.keyCount + 1, right.keyCount);
  System.arraycopy(right.values, 0,
                   left.values, left.keyCount + 1, right.keyCount);
  left.keyCount = left.keyCount + 1 + right.keyCount;

  // 統合で1つ余分になった親の要素・子を左へずらして詰める
  ...
  parent.keyCount--;
}
//}

//noindent
削除対象のキーがリーフではなく内部ノードにある場合は少し工夫が必要です。内部ノードのキーを削除すると、そのキーは「左側の子の全要素と右側の子の全要素の境界」としての役割を持つため、単純に配列から消すだけでは木のルールが崩れます。そこで、次のいずれかで対処します。

 * @<b>{先行要素（左部分木の最大のキー）で置き換える}: 左の子に十分なキーがあるとき。境界キーを左部分木の最大キーで置いたなら、左部分木でそのキーを削除する問題（より深い階層での削除）に帰着できる。
 * @<b>{後続要素（右部分木の最小のキー）で置き換える}: 前者ができず、右の子に十分なキーがあるとき。
 * @<b>{統合してから削除する}: 左右どちらの子も下限のとき。2つの子を統合した後、統合した子内でキーの削除を続行する。

//emlist[deleteInternalKeyメソッド（要約）][java]{
// 内部ノードの index 番目のキーを削除する
private void deleteInternalKey(Node node, int index) {
  Node left = node.children[index];
  Node right = node.children[index + 1];

  // 左の子に十分なキーがある場合、左部分木の最大要素（先行要素）で置き換える
  if (left.keyCount >= minimumDegree) {
    KeyValue predecessor = maximum(left);
    node.keys[index] = predecessor.key();
    node.values[index] = predecessor.value();
    delete(left, predecessor.key());
  } else if (right.keyCount >= minimumDegree) {
    // 右の子に十分なキーがある場合、右部分木の最小要素（後続要素）で置き換える
    KeyValue successor = minimum(right);
    node.keys[index] = successor.key();
    node.values[index] = successor.value();
    delete(right, successor.key());
  } else {
    // 左右の子がいずれも下限なら、2つを統合してから統合後の子で削除を続ける
    String removedKey = node.keys[index];
    mergeChildren(node, index);
    delete(left, removedKey);
  }
}
//}

//noindent
最後に、ルートの処理です。統合が進んで内部ノードのルートがキーをなくしたとき（@<code>{keyCount == 0}）、唯一の子を新しいルートにして木の高さを1つ下げることで、無駄な階層を解消しています。

//emlist[ルートの縮小][java]{
public void delete(String key) {
  Objects.requireNonNull(key, "key");
  delete(root, key);

  // 統合により内部ルートがキーを持たなくなった場合、
  // 唯一の子を新しいルートにして高さを詰める
  if (!root.leaf && root.keyCount == 0) {
    Node oldRoot = root;
    root = oldRoot.children[0];
    oldRoot.children[0] = null;
  }
}
//}

== 起動時のB-Tree構築

ここまでで作ったB-Treeは、あくまでもメモリ上のデータ構造です。データベースの実体はディスク上のページなので、起動時のメモリ上にこのインデックスがない状態では、一切の検索ができません。そこで、データベースサーバー起動時に、ディスクの全ページを1回だけ走査してインデックスを再構築します。

この役割を担うのが、PageManagerクラスのbuildMetadataメソッドです。

//emlist[PageManagerクラス：起動時のメタデータ構築][java]{
private void buildMetadata() throws IOException {
  int numPages = diskManager.getPageCount();
  for (int p = 0; p < numPages; p++) {
    Page page = diskManager.readPage(p);
    // ① FSM（空き容量マップ）の構築
    fsm.addPage(page.getTotalFreeSpace());
    // ② B-Tree の構築
    for (int lpIndex = 0; lpIndex < page.getNumLinePointers(); lpIndex++) {
      Record record = page.getRecord(lpIndex);
      if (record != null) {
        if (index.search(record.key()) != null) {
          throw new IOException("Duplicate key in database: " + record.key());
        }
        index.insert(record.key(), new TupleId(p, lpIndex));
      }
    }
  }
}
//}

//noindent
このメソッドは、ページを1つ読むごとに2つの処理を行います。

 * @<b>{空き容量マップ（FSM）の更新}: 第3章で導入したFSMは、メモリ上にしか存在しません（ディスクには保存していない）。このため、起動時に走査して各ページの空き容量を再計算します。
 * @<b>{B-Treeの再生}: 各ページの中身（スロット）を読み、「このキーはこのTupleIdだ」という対応をB-Treeへ登録します。

//noindent
なお、同じキーを2回登録しようとした場合（データベースファイルの破損など）には、@<code>{IOException}を投げて起動を中止する安全性のチェックも組み込まれており、不正なデータファイルへ気づくことができるようになっています。

起動時の全ページ走査は、データ量が多いと起動に時間がかかるというコストを持ちます。本書ではこの方式を採用していますが、実際のデータベースシステムでは、インデックスそのものもディスク上に永続化し、ログ（変更履歴）をもとにこまめに立ち上げ直す、いわゆるリカバリという仕組みでこのコストを最小限に抑えています。

== B-Treeを用いたCRUDの高速化

構築したB-Treeを活用し、実際のデータベース操作（@<code>{select}, @<code>{insert}, @<code>{update}, @<code>{delete}）の処理ロジックを、ループによる全件走査から直接アクセスへと書き換えます。

これにより、前章までの課題であった「線形探索によるパフォーマンス限界」を解消し、データ件数に依存しない O(log N) の安定した応答速度を実現します。

具体的には、検索処理では全ページ走査の @<code>{for} ループを削除し、@<code>{index.search(key)} で取得した位置情報（TupleId）へ @<code>{readPage()} で直接アクセスします。挿入・削除処理でも、同様にB-Treeで位置を特定してから操作を行い、操作後にはB-Treeを更新し忘れないように注意することが重要です。

==== 検索、重複チェック

PageManagerのfindRecordがどう変わるかを見てみましょう。前章までの実装では、全ページを読み込み、スロットを順番に調べてキーの一致を確認していました。本章では、まずB-Treeで位置を特定し、そのページだけを読み込みます。

//emlist[findRecordメソッド：B-Treeによる直接アクセス][java]{
public Record findRecord(String key) throws IOException {
  if (key == null) {
    return null;
  }
  // 1. B-Tree から TupleId を取得（O(log N)）
  TupleId tupleId = index.search(key);
  if (tupleId == null) {
    return null;
  }
  // 2. 対象のページを1枚だけ読み込む
  Page page = readPage(tupleId.pageIndex());
  // 3. ページ内の該当スロットからレコードを取り出す
  return indexedRecord(page, key, tupleId);
}
//}

//noindent
@<code>{indexedRecord} メソッド（中略）では「指定されたTupleIdのスロットにあるレコードが、本当に要求されたキーのものであるか」を確認しています。インデックスとデータ本体の整合性が崩れていた場合には例外を送出するため、システムがおかしなデータを返す前に問題に気づけるようになっています（このような防御的なチェックは、実運用のデータベースには欠かせないものです）。

組み込み操作（insert時の重複チェック）も同じです。前章までは @<code>{findRecord(key) != null} を調べるために全ページ走査が発生していましたが、本章では @<code>{index.search(key)} が null を返すかどうかを B-Tree 上だけで（ディスクのページ読み込み無しに、というわけではないが O(log n) で）判断できるようになります。

//emlist[insertメソッドの重複チェック（一部）][java]{
if (index.search(key) != null) {
  throw new IllegalArgumentException(
      "Key already exists. Use update command to modify.");
}
//}

==== 削除と挿入の一貫性

削除と挿入では、@<b>{B-Treeとページの2つのデータ構造の更新を正しく組み合わせる}必要が出てきます。コマンドを間違えると木は「レコードが存在すると思っているのに、ディスク上にはない」といった不整合が発生してしまいます。

削除(delete)処理の流れは次の通りです。

 1. @<code>{index.search(key)} でTupleIdを取得。
 2. TupleIdのページを読み込み、該当スロットを LP_DEAD にしてページを書き戻す。
 3. @<code>{index.delete(key)} でB-Treeからキーを削除する。

//noindent
挿入(insert)処理の流れは次の通りです。

 1. @<code>{index.search(key)} による重複確認。
 2. FSMに応じて挿入先ページを決定し、レコードを登録する。
 3. 登録結果のTupleIdを @<code>{index.insert(key, tupleId)} でB-Treeへ登録する。

//noindent
updateでも同様で、新しいTupleIdが決まったら必ず @<code>{index.insert(key, newTupleId)}（upsert）を行います。B-Treeのinsertメソッドは「既存キーなら値を差し替える」設計になっているため、TupleIdの更新も同じメソッドで処理できます。

==== 実行速度の比較：どれくらい速くなったのか

ここまで実装してきた「B-Treeによる検索の高速化」によって、実際のデータベース操作がどれほど改善されたのかを確認してみましょう。

あらかじめデータベースに1万件のデータ（ID: 1〜10000）を挿入した状態で、前章（線形探索）と本章（B-Tree）のプログラムに対して同じコマンドを実行し、処理時間を比較しました。

//table[performance_comparison][実行時間の比較（1万件のデータが存在する状態）]{
コマンド	前章（線形探索）	本章（B-Tree）
-------------------------------------------------------------
@<code>{select 8765}	11.403 ms	0.686 ms
@<code>{update 8765 test}	5.933 ms	2.960 ms
@<code>{delete 1}	1.628 ms	0.991 ms
@<code>{delete 8765}	3.257 ms	0.983 ms
@<code>{insert 10001 test}	3.310 ms	1.218 ms
//}

//noindent
※実行環境により数値は変動します。数値はあくまで一例として参照してください。

結果は一目瞭然です。ファイルの先頭から順にページを読み込んでいた @<code>{select} 処理は、B-Treeを導入したことで @<b>{16倍以上もの高速化} を果たしています。

特に注目していただきたいのが @<code>{delete} の結果です。
前章の線形探索では、ファイルの先頭付近にある @<code>{delete 1}（1.628 ms）に比べて、末尾付近にある @<code>{delete 8765}（3.257 ms）は、対象を見つけるまでに多くのデータを読み込む必要があり、処理時間が2倍近くかかっていました。
しかし、本章の実装では @<code>{delete 1} も @<code>{delete 8765} も @<b>{どちらも約0.9 ms} で完了しています。B-Treeは木がバランスを保つ（すべての葉までの深さが同じになる）ため、@<b>{データがファイルのどこにあっても、常に一定の速度（O(log N)）でアクセスできる}という強力な特性が数値として美しく表れています。

また、新しいデータを追加する @<code>{insert} についても、挿入前の「キーの重複チェック」がB-Treeで高速化されたことで、処理時間が大幅に短縮されています。

== まとめと次章への課題

本章では、データベースの検索・更新処理を高速化するB-Treeを実装しました。

 * レコードの位置を表すTupleIdを、昇順ソート済みのキー配列とセットでB-Treeのノードに保持した。
 * 検索は、ノード内の二分探索で進むべき枝を決め、最も深いレベルまで降りていくだけの処理になった。
 * 挿入では、降下中に満杯の子の分割を先に済ませる方式で、木のバランスを崩さない挿入を実現した。
 * 削除では、兄弟からのキーの借用（Borrow）と兄弟間の統合（Merge）によって、ノードが最小キー数を下回る「アンダーフロー」を解消した。
 * 起動時にディスクを1回だけ走査してインデックスを再構築することで、ディスク上のデータとインデックスを紐づけた。

これらを導入したことにより、前章で直面した「線形探索によるパフォーマンスの限界」を見事に克服しました。検索対象のデータを特定する処理が O(log N) の時間計算量へと改善され、データ挿入時の重複チェックのディスクI/Oも削減されました。これにより、内部的なデータアクセスの仕組みは実践的な速度で動作するようになりました。

しかし、データベースの「内部の処理速度」は向上したものの、ユーザーがデータベースを操作するための「インターフェース」には課題が残されています。

 * @<b>{操作方法が複雑で扱いにくい:}
   現在の仕様では、データの挿入や検索を行うために、@<code>{insert 1 Alice} のような独自コマンドの形式をいちいち思い出して入力する必要があります。これではアプリケーションから汎用的かつ直感的に利用することができません。

 * @<b>{「何を」取得するかは分かるが、「どう」実行するかが決まっていない:}
   現在は「B-Treeの @<code>{search} を呼んでからファイルを読む」という単純な手順が決め打ちになっています。今後「特定の条件を満たすレコードだけを取得したい」といった要求が出た場合、ユーザー自身が「どの仕組みを使って、どの順番でデータを読み込むか」という具体的な手続き（手段）まで意識しなければならなくなってしまいます。

現代のリレーショナルデータベースが広く使われている最大の理由は、ユーザーが「欲しいデータ（目的）」だけを宣言的に記述すれば、データベース側が自動的に「最適な検索手順（手段）」を判断して実行してくれる点にあります。

次章では、この課題を解決するために @<b>{SQL} の処理エンジンをデータベースに組み込みます。「何をするか」を記述する標準言語であるSQLを読み解くため、字句解析（トークナイズ）や構文解析（パース）、そして抽象構文木（AST）の構築といった、言語処理系の領域へと足を踏み入れます。
まずは第一歩として、@<code>{SELECT} 文や @<code>{INSERT} 文をプログラムが理解できる形に変換する仕組みを作っていきましょう。


===[column] B+Treeは何が違い、何が嬉しいのか

B-TreeとB+Treeの違いは、@<b>{実データ（TupleId など）をどこに置くか}です。B-Treeは内部ノードにも値を持てますが、B+Treeは値を葉ノードに集約し、内部ノードは「キーと子ポインタ」だけを持ちます。

この設計により、B+Treeは内部ノードを高密度にでき、木が浅くなりやすくなります。例えばページ4KB、キー8B、ポインタ8B、値8Bとすると、内部ノードの分岐数は概算で B-Tree が約165、B+Tree が約248です。分岐数が増えるほど高さは下がるため、@<b>{10億件規模で1回I/O程度が減る}ケースが現実的に起こります。

さらにB+Treeは葉ノード同士を連結するため、範囲検索で特に強みがあります。開始位置を見つけた後は葉を順に読むだけでよく、@<b>{連続した多数件の取得で再探索コストを抑えられる}ためです。

つまりB+Treeの価値は、@<b>{点検索のI/O削減と範囲検索の効率化を同時に得られる}点にあります。本章のB-Tree実装は、その設計思想に到達するための土台です。

===[/column]
