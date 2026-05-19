# grader-base/Dockerfile.template — SỬA LẠI ĐƯỜNG DẪN COPY

FROM ghcr.io/cirruslabs/flutter:stable

RUN apt-get update && apt-get install -y \
    xvfb libgtk-3-0 libglu1-mesa xauth \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# ✅ File nằm thẳng ở root build context — không có grader_template/
COPY pubspec.yaml ./pubspec_base.yaml
COPY pubspec.yaml ./pubspec.yaml

# Tải packages vào image (cần internet lúc build)
RUN flutter pub get
RUN flutter precache --universal

# ✅ test/ và scripts/ nằm thẳng ở root
COPY test/    ./test/
COPY scripts/ ./scripts/

RUN mkdir -p lib

COPY run_grader.sh .
# Convert CRLF → LF (Windows editors break Linux shebangs)
RUN sed -i 's/\r//' run_grader.sh && chmod +x run_grader.sh

CMD ["./run_grader.sh"]