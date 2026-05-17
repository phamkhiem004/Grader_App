FROM ghcr.io/cirruslabs/flutter:stable

RUN apt-get update && apt-get install -y \
    xvfb libgtk-3-0 xauth \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# pubspec GV — làm base để merge
COPY grader_template/pubspec.yaml ./pubspec_base.yaml
COPY grader_template/pubspec.lock ./pubspec.lock

# Pre-cache packages của GV vào image
RUN flutter pub get
RUN flutter precache --universal

# Copy testcase + grader + scripts
COPY grader_template/test/      ./test/
COPY grader_template/scripts/   ./scripts/

# lib/ và pubspec_student.yaml sẽ được mount lúc chấm
RUN mkdir -p lib

COPY run_grader.sh .
RUN chmod +x run_grader.sh

CMD ["./run_grader.sh"]