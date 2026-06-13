FROM gradle:8-jdk21

WORKDIR /app

# コンテナが起動し続けるように待機させる
CMD ["tail", "-f", "/dev/null"]
