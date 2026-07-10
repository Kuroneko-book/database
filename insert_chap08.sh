#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

DATA_DIR="$(pwd)/chapter08/data"
CATALOG_FILE="$DATA_DIR/catalog.txt"

# Remove existing database files if they exist in the data folder
if [ -d "$DATA_DIR" ]; then
  echo "Removing existing data files in $DATA_DIR..."
  rm -f "$CATALOG_FILE" "$DATA_DIR"/*.tbl
fi

# Ensure the development container is running
if [ -z "$(docker compose ps -q dev)" ]; then
  echo "Starting Docker container..."
  docker compose up -d
fi

echo "Cleaning up existing chapter08 database files..."
rm -f "$CATALOG_FILE" "$DATA_DIR"/*.tbl

echo "Running chapter08 SQL flow..."
docker compose exec -T dev gradle :chapter08:run --console=plain <<'EOF'
create table users (id integer index, name string(20));
create table addresses (id integer index, user_id integer index, city string(20));
insert into users values (1, 'Alice');
insert into users values (2, 'Bob');
insert into addresses values (1, 1, 'Tokyo');
insert into addresses values (2, 2, 'Osaka');
exit
EOF

# echo "Deleting chapter08 database files..."
# rm -f "$CATALOG_FILE" "$DATA_DIR"/*.tbl

# echo "Flow completed. catalog.txt and table files have been removed."
