FROM eclipse-temurin:21-jdk

WORKDIR /app

# コンテナが起動し続けるように待機させる
CMD ["tail", "-f", "/dev/null"]
