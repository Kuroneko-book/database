= 最小のデータベース

前章では、これから構築するデータベースのロードマップを示しました。
本章では、その第一段階として、まずは対話型のプログラム（REPL）を作り、メモリ上だけで動く最小のデータベースを作ります。

== 本章の方針：メモリ上で動く最小構成

本章では、まだディスク（ファイル）には触れず、まずはプログラムの実行中のみデータを保持する「オンメモリ」のデータベースを作成します。本格的なアーキテクチャを組む前に、データベースの「外枠」となる対話型のインターフェースと、最も基本的なデータ操作の仕組みを完成させることが目的です。

具体的には、以下の順序で実装を進めます。

 1. @<b>{REPLの作成}: ユーザーからの入力を無限ループで受け付け、結果を返す「対話型プログラム」の土台を作ります。
 2. @<b>{入力とコマンドの扱い}: 入力された単なる文字列を、「コマンド」と「操作対象のデータ」に分割して解釈する仕組み（パーサ）を作ります。
 3. @<b>{CRUD操作とオンメモリ管理}: ハッシュテーブル（HashMap）を利用して、データを登録・取得・更新・削除（CRUD）するデータベースのコア機能を実装します。

このステップを踏むことで、まずは「動くデータベースの雛形」を手に入れることができます。さっそく、ユーザーとの対話の窓口となるREPLの作成から始めましょう。

== 対話型プログラム（REPL）の作成

データベースは、一度起動したらすぐに終了するプログラムではありません。
ユーザーからの入力を待ち続け、入力された命令を実行し、その結果を表示することを繰り返します。
例えば、多くのデータベースでは次のような対話形式で操作を行います。

//cmd{
> SELECT * FROM users;
1|Alice|20
2|Bob|18
//}

//noindent
このようなプログラムは@<b>{REPL（Read-Eval-Print Loop）}と呼ばれます。
REPLとは、以下の4つの処理から構成されます。

 * @<b>{Read}：ユーザーから入力を受け取る
 * @<b>{Eval}：入力された命令を実行する
 * @<b>{Print}：実行結果を表示する
 * @<b>{Loop}：これらを繰り返す

//noindent
本書で作成するデータベースも、このREPL形式を採用します。

==== 最初のデータベース

まずはデータベース本体となるMiniDBクラスを作成します。
コンストラクタでは、データを保持するためのHashMap、キーボード入力を受け取るScanner、入力を解析するためのSimpleParserを初期化します。

//emlist[MiniDBクラスのコンストラクタ][java]{
public MiniDB() {
  db = new HashMap<>();
  scanner = new Scanner(System.in);
  parser = new SimpleParser();
  System.out.println("Welcome to minidb!");
}
//}

//noindent
現時点では、このHashMapがデータベースそのものになります。
プログラムを起動すると、以下のメッセージが表示され、データベースの開始を知らせます。

//cmd{
Welcome to minidb!
//}

==== REPLの実装

データベースが起動したら、ユーザーが終了を指示するまで入力を受け付け続けます。
そのために、MiniDBクラスのstartメソッドに無限ループを用意しています。

//emlist[startメソッド：入力を待ち続ける無限ループ][java]{
public void start() {
  while (true) {
    System.out.print("minidb > ");
    System.out.flush();

    if (!scanner.hasNextLine()) {
      break;
    }

    String[] tokens = parser.parse(scanner.nextLine());
    String command = parser.getCommand(tokens);

    // コマンドへの振り分け（本章の後半で実装します）
  }
}
//}

//noindent
このコードを実行すると、画面には次のように表示され、ユーザーの入力待ち状態になります。
ここでユーザーがコマンドを入力すると、入力された文字列が1行分読み込まれます。

//cmd{
minidb >
//}

//noindent
プロンプトには「minidb > 」という文字列を表示しています。
@<code>{System.out.flush()} は、@<code>{print} で書いた文字列をすぐに画面へ押し出すための呼び出しです。@<code>{print} で書いた文字列は改行が出力されるまで表示が確定しないことがあるため、REPLのような対話的な処理では毎回明示的に押し出しておくと安心です。

==== プログラムの実行方法

作成したデータベースは、Gradleのrunタスクで実行できます。
第1章のコードは minidbchap1 というディレクトリに配置されているため、次のように起動します。

//cmd{
docker compose exec dev gradle :minidbchap1:run --console=plain
//}

//noindent
@<code>{--console=plain} を付与するのは、対話型プログラムのプロンプト表示が崩れないようにするためです。
以降の章も、@<code>:minidbchapN と章番号を変えるだけで同じ方法で起動できます。

== 入力とコマンドの扱い

前節ではユーザーからの入力を受け付けるREPLを作成しました。
しかし、この時点では入力された文字列は単なる文字列であり、プログラムはその意味を理解できません。
例えば、@<code>{insert 1 Alice}という入力があったとしても、プログラムにとっては@<code>{"insert 1 Alice"}という一つの文字列に過ぎません。
本節では、この文字列を解釈するための処理を実装していきます。

データベースとして動作させるためには、REPLにて入力を受け付けた文字列を、

 * 実行するコマンド
 * 操作対象のデータ（引数）

//noindent
に分けて解釈する必要があります。
本書では、この役割を@<b>{SimpleParser}クラスが担当します。
まず、入力された文字列を空白で分割する処理をSimpleParserクラスに実装します。

//emlist[SimpleParserクラス：入力文字列の分割][java]{
public String[] parse(String sql) {
  String[] tokens = sql.trim().split("\\s+");
  tokens[0] = tokens[0].toLowerCase();
  return tokens;
}
//}

//noindent
@<code>{trim()} で前後の余計な空白を取り除き、@<code>{split("\\s+")} で連続した空白を区切り文字として配列に分割します。
さらに、配列の先頭の要素（コマンド名）を小文字に変換しています。これは、@<code>{INSERT} と @<code>{insert} を同じコマンドとして扱えるようにするためです。
入力の解析結果は次のようになり、コマンドと引数を個別に扱えるようになります。

//table[入力の解析結果][入力の解析結果]{
入力	分割結果
-------------------------------------------------------------
@<code>{insert 1 Alice}	@<code>{["insert","1","Alice"]}
@<code>{select}	@<code>{["select"]}
@<code>{delete 5}	@<code>{["delete","5"]}
//}

//noindent
データベースが最初に知りたいのは、「どの操作を実行するのか」です。
そこで、分割した配列の最初の要素をコマンド名として取り出します。

//emlist[SimpleParserクラス：コマンド名の取り出し][java]{
public String getCommand(String[] tokens) {
  return tokens.length > 0 ? tokens[0] : "";
}
//}

//noindent
例えば、@<code>{["insert", "1", "Alice"]}であれば、@<code>{"insert"}が返されます。
これにより、以降の処理では入力全体を調べる必要がなくなり、コマンド名だけを見て処理を分岐できるようになります。

==== コマンドへの振り分け

MiniDBクラスに戻り、取得したコマンド名をもとに処理を切り替えるようstartメソッドを拡張します。
コマンド名に加えて、引数の個数（@<code>{tokens.length}）もあわせて確認している点に注目してください。例えば、insertコマンドは「コマンド名・キー・値」の3つの要素を必要とするため、@<code>{tokens.length == 3} のときだけ実行します。引数の個数が合わない入力は、else節の @<code>{Unknown command} に流れ、誤った使い方をされたときにユーザーへ気づきを与えられます。

//emlist[startメソッド：コマンドへの振り分け][java]{
public void start() {
  while (true) {
    // ユーザーの入力待ち（中略）

    String[] tokens = parser.parse(scanner.nextLine());
    String command = parser.getCommand(tokens);

    // コマンドが空の場合はスキップ
    if (command.isEmpty()) {
      continue;
    } else if (command.equals("insert") && tokens.length == 3) {
      insert(tokens[1], tokens[2]);
    } else if (command.equals("select")) {
      if (tokens.length == 1) select();
      else if (tokens.length == 2) select(tokens[1]);
    } else if (command.equals("update") && tokens.length == 3) {
      update(tokens[1], tokens[2]);
    } else if (command.equals("delete") && tokens.length == 2) {
      delete(tokens[1]);
    } else if (command.equals("exit")) {
      System.out.println("Bye!");
      break;
    } else {
      System.out.println("Unknown command");
    }
  }
}
//}

//noindent
この分岐処理によって、例えば@<code>{insert 1 Alice}と入力すると、@<code>{insert("1", "Alice")}が呼び出されます。
このように、入力された文字列を解析し、適切なメソッドへ処理を振り分けることで、データベースはさまざまな命令を実行できるようになります。

==== キーの整数変換

データベースはキー（レコードのID）を整数として扱います。
そのため、各CRUDメソッドは、受け取った文字列を整数へ変換してから処理を行います。変換は、parseKeyメソッドにまとめられています。

//emlist[parseKeyメソッド：キーの整数変換][java]{
private Integer parseKey(String key) {
  try {
    return Integer.parseInt(key);
  } catch (NumberFormatException e) {
    System.out.println("Error: Key must be an integer.");
    return null;
  }
}
//}

//noindent
例えば、@<code>{insert 1 Alice}と入力すると、@<code>{tokens[1]}の"1"が数値の1に変換されてレコードが登録されます。
一方、@<code>{insert abc Alice}のように数値として解釈できない文字列がキーとして入力された場合は、@<code>{Error: Key must be an integer.} と表示して処理を中断します。変換結果が@<code>{null}になった場合、各メソッドの先頭で処理を抜けるようになっているためです。

//cmd{
minidb > insert abc Alice
Error: Key must be an integer.
//}

//noindent
文字列の解析（どこで区切るか）と文字列の解釈（どの型へ変換するか）を分けて実装すると、このようにエラー処理を一か所にまとめられるようになります。

== CRUD操作の実装

前節までで、ユーザーが入力したコマンドを解析し、実行する処理を決定できるようになりました。
しかし、この時点ではデータベースの実体としての機能はまだありません。
そこで本節では、データベースの基本操作である@<b>{CRUD}を実装します。
CRUDとは、データを扱うための4つの基本操作の頭文字を取ったものです。

//table[CRUD][CRUD]{
操作	コマンド	説明
-------------------------------------------------------------
Create	@<code>{insert 1 Alice}	キー1でAliceを登録する
Read 	@<code>{select}・@<code>{select 1}	データを取得する
Update	@<code>{update 1 Carol}	キー1のデータをCarolへ変更する
Delete	@<code>{delete 1}	キー1のデータを削除する
//}

//noindent
多くのデータベースは、これら4つの操作を基本として構成されています。本書でも、まずはこの最小限の機能を実装していきます。

==== insert

新しいデータを登録するには、@<b>{insert}コマンドを使用します。

//cmd{
minidb > insert 1 Alice
Inserted.
minidb > insert 1 Bob
Key already exists.
//}

//noindent
insertメソッドでは、キー（ID）の重複チェックを行い、HashMapにキーと値の組み合わせを追加します。

//emlist[insertメソッド][java]{
public void insert(String key, String value) {
  Integer id = parseKey(key);
  if (id == null) {
    return;
  }

  if (db.putIfAbsent(id, value) != null) {
    System.out.println("Key already exists.");
    return;
  }

  System.out.println("Inserted.");
}
//}

//noindent
ここで使用している @<code>{putIfAbsent()} は、「キーがまだ存在しない場合だけ値を登録する」メソッドです。キーがすでに存在していた場合、@<code>{putIfAbsent()} は登録済みの値を返すため、その戻り値を確認すれば重複チェックができます。単純な @<code>{put()} を使うと、気付かずに既存のデータを上書きしてしまうため、登録には @<code>{putIfAbsent()} を用います。

==== select

登録したデータを確認するには、@<b>{select}コマンドを使用します。
selectコマンドには2つの使い方（引数なし、引数あり）があるため、メソッドをオーバーロード（同名で引数が違うメソッドを定義）して実装します。

1つ目の使い方は、キーを指定せず、登録されているすべてのデータを取得します。

//cmd{
minidb > select
(1,Alice)
(2,Bob)
//}

//noindent
実装では、HashMapのすべての要素を順番に取り出して表示します。

//emlist[selectメソッド：全件表示][java]{
public void select() {
  db.forEach((id, name) ->
      System.out.println("(" + id + "," + name + ")"));
}
//}

//noindent
2つ目の使い方は、キー（ID）を指定し、そのデータだけを取得します。

//cmd{
minidb > select 2
minidb > select 99
Bob
Record not found.
//}

//noindent
実装では、指定されたキーの存在を確認し、HashMapから取り出して表示します。

//emlist[selectメソッド：キー指定の検索][java]{
public void select(String key) {
  Integer id = parseKey(key);
  if (id == null) {
    return;
  }

  if (!db.containsKey(id)) {
    System.out.println("Record not found.");
  } else {
    System.out.println(db.get(id));
  }
}
//}

==== update

登録済みのデータを変更するには、@<b>{update}コマンドを使用します。

//cmd{
minidb > update 2 Robert
Updated.
//}

//noindent
updateメソッドでは、まず対象のキーが存在するかを確認し、存在する場合のみ対応する値を上書きします。

//emlist[updateメソッド][java]{
public void update(String key, String value) {
  Integer id = parseKey(key);
  if (id == null) {
    return;
  }

  if (!db.containsKey(id)) {
    System.out.println("Record not found.");
    return;
  }

  db.put(id, value);
  System.out.println("Updated.");
}
//}

//noindent
insertと違い、updateは既存のデータを上書きする意図の操作なので、単純な @<code>{put()} を使用しています。

==== delete 

不要になったデータは、@<b>{delete}コマンドで削除します。

//cmd{
minidb > delete 1
Deleted.
//}

//noindent
deleteメソッドでも同様に、対象のキーが存在するか確認してから削除処理を行います。

//emlist[deleteメソッド][java]{
public void delete(String key) {
  Integer id = parseKey(key);
  if (id == null) {
    return;
  }

  if (!db.containsKey(id)) {
    System.out.println("Record not found.");
    return;
  }

  db.remove(id);
  System.out.println("Deleted.");
}
//}

== ハッシュテーブルによるオンメモリ管理

前節では、insert、select、update、deleteの4つの基本操作を実装しました。
これらの処理では、データを保存するための共通の仕組みとしてJavaの@<b>{Map}を利用しています。
本節では、これがどのようなデータ構造であり、データベースの内部でどのような役割を果たしているのかを見ていきます。

==== ハッシュテーブル（Map）とは

JavaのMapは、コンピュータサイエンスにおける「連想配列」や「ハッシュテーブル」と呼ばれる、キーと値のペアを管理するデータ構造の実装です。
例えば、次のようなデータがあるとします。

//table[キー（ID）と値（Name）のペア][キー（ID）と値（Name）のペア]{
キー（ID）	値（Name）
-------------------------------------------------------------
1	Alice
2	Bob
3	Carol
//}

//noindent
Mapを使うと、この表のような対応関係をそのままプログラム上で表現できます。
本書では、このキーを「レコードのID」、値を「レコードの内容」として扱います。
プログラムでは、データベース本体を次のように宣言しています。

//emlist[MiniDBクラスの 필드定義][java]{
public class MiniDB {
  // データベース本体（キーは数値型のID、値は文字列）
  private Map<Integer, String> db;
  private Scanner scanner;
  private SimpleParser parser;

  public MiniDB() {
    db = new HashMap<>();
    // その他の初期化処理（中略）
  }
  // その他のメソッド（中略）
}
//}

==== なぜHashMapを使うのか

Mapにはいくつかの実装種類がありますが、本書ではハッシュテーブルを利用した @<code>{HashMap} を採用しています。
その最大の理由は、指定したキーに対応する値を「極めて高速に検索できる」ためです。

//cmd{
minidb > select 2
//}

//noindent
を実行すると、内部では @<code>{db.get(2);} が呼ばれます。
HashMapは内部的にハッシュ関数という仕組みを使ってデータの保存場所を一瞬で特定するため、データ数が数件でも数十万件に増えても、キーを指定した検索にかかる時間はほとんど変わりません（計算量 @<m>{O(1)}）。

前節で実装した4つの操作は、すべてこのMapの基本メソッドに1対1で対応しています。

//table[DB操作とMapの操作の対応][DB操作とMapの操作の対応]{
DB操作	Mapの操作
-------------------------------------------------------------
@<code>{insert}	@<code>{putIfAbsent()}
@<code>{select}	@<code>{get()}
@<code>{update}	@<code>{put()}
@<code>{delete}	@<code>{remove()}
//}

==== 例外を使わないCRUDの設計

ここまでで実装した4つのメソッドには、共通のパターンがあります。

 1. まず @<code>{parseKey()} でキーを整数へ変換し、失敗したらメッセージを出して処理を中断する。
 2. 次にキーの存在確認（@<code>{containsKey()} や @<code>{putIfAbsent()} の戻り値）を行い、操作が成立しない場合もメッセージを出して中断する。
 3. 最後に操作を実行して、結果をユーザーへ報告する。

//noindent
共通のパターンとしてまとめたことによる利点は、「失敗してもプログラムを終了しない」ということです。
REPLは対話的なプログラムなので、入力ミスや操作ミスがそれ自体ではなく、処理を続行できる状態であることが必要です。メソッド内でエラーを検出して返り値やメッセージで対処するスタイルは、後の章でストレージ層との連携が始まっても同じ形で利用できるため、本書の設計の基本として尊重しています。

== まとめと次章への課題

本章では、JavaのHashMapを利用して、データベースの最小構成を実装しました。
ユーザーからの入力をREPLで受け付け、コマンドを解析し、CRUD操作を実行することで、「データを登録・取得・更新・削除する」というデータベースの基本機能を実現しました。
ここまでの処理の流れをまとめると、次のようになります。

//emlist[minidbchap1（第1章）の処理の流れ]{
 REPL: Read / Eval / Print / Loop
+--------------------------------------+
|                 REPL                 |  <- 対話ループで入力を受け付ける
+------------------+-------------------+
                   |
                   v
+--------------------------------------+
|             SimpleParser             |  <- 入力を「コマンドと対象」に解析
+------------------+-------------------+
                   |
                   v
+--------------------------------------+
|                 MiniDB               |  <- CRUD の本体
+------------------+-------------------+
                   |
                   v
+--------------------------------------+
|                HashMap               |  <- key と value を保持
+--------------------------------------+
//}

//noindent
この構成はシンプルで理解しやすく、小規模なデータベースとして十分に機能します。
しかし、データがメモリ上にしか存在しないため、プログラムを終了するとすべて失われてしまいます。
実際のデータベースでは、サーバーを停止したりプログラムを終了したりしても、登録したデータは保持されなければなりません。

そのため、次章ではこの問題を解決するために、データをファイルへ保存する@<b>{「永続化」}の仕組みを実装します。
これにより、プログラムを再起動してもデータを保持できる、本格的なデータベースへと一歩近づいていきます。
