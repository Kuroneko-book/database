#!/bin/bash

# 引数から実行する章（CHAP）を取得（デフォルトは chapter01）
CHAP=${1:-chapter01}

echo "10000件のデータを生成中..."

# データ格納用のディレクトリをきれいにクリーンアップする（SQLベースの章の場合）
if [[ "$CHAP" == "chapter05" || "$CHAP" == "chapter06" || "$CHAP" == "chapter07" ]]; then
    echo "古いデータベースファイルを削除しています..."
    rm -f "$CHAP/data/catalog.txt" "$CHAP/data/users.tbl" "$CHAP/data/catalog.txt.tmp"
fi

# 一時ファイルを作成
DATA_FILE="data.txt"
> "$DATA_FILE"

if [[ "$CHAP" == "chapter05" || "$CHAP" == "chapter06" || "$CHAP" == "chapter07" ]]; then
    if [[ "$CHAP" == "chapter05" ]]; then
        # chapter05: インデックスなしの SQL (idは内部で自動的にインデックスが貼られます)
        echo "CREATE TABLE users (id INT, name VARCHAR(50));" >> "$DATA_FILE"
    else
        # chapter06, chapter07: インデックスありの SQL
        echo "CREATE TABLE users (id INT INDEX, name VARCHAR(50));" >> "$DATA_FILE"
    fi

    for i in $(seq 1 10000)
    do
        echo "INSERT INTO users (id, name) VALUES ($i, 'user$i');" >> "$DATA_FILE"
    done
else
    # chapter01 - chapter04: コマンド型
    for i in $(seq 1 10000)
    do
        echo "insert $i user$i" >> "$DATA_FILE"
    done
fi

echo "exit" >> "$DATA_FILE"

echo "Dockerコンテナ内のプログラムにデータを投入します ($(CHAP))..."

# Dockerコンテナのプログラムに対して、生成したテキストファイルを標準入力として流し込む
docker compose exec -T dev gradle :"$CHAP":run --console=plain < "$DATA_FILE"

rm -f "$DATA_FILE"

echo "データ投入が完了しました。"
