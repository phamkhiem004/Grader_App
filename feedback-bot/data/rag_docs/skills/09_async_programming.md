# Skill: Lập trình bất đồng bộ (Asynchronous Programming)

- **skill (lớn):** `async_programming`
- **Tên skill:** Lập trình bất đồng bộ trong Flutter/Dart
- **Nguồn:** *Mastering Flutter* (Kevin Moore, 2025), Chương 10 — "Futures and Async/Await".
- **skill_code trong skill này:** `async_future_async_await`, `async_event_loop`, `async_futurebuilder`, `async_streams_streambuilder`, `async_isolates`.

---

## skill_code: `async_future_async_await`
**skill_name:** Future và async/await · **skill:** `async_programming`

### Khái niệm
`Future<T>` đại diện cho một giá trị sẽ có trong tương lai, dùng làm kiểu trả về cho hàm bất đồng bộ (gọi mạng, I/O, tính toán nặng). `async` đánh dấu hàm bất đồng bộ và bắt buộc hàm trả về `Future`. `await` (chỉ dùng trong hàm `async`) chờ kết quả trước khi chạy tiếp.

### Dấu hiệu đạt yêu cầu
- Hàm có tác vụ chờ được khai báo `async`, trả về `Future<T>` đúng kiểu.
- Dùng `await` để lấy kết quả thay vì giả định dữ liệu đã sẵn sàng.
- Có bắt lỗi cho tác vụ bất đồng bộ (try/catch hoặc `catchError`).

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Quên `await` → code chạy tiếp khi dữ liệu chưa về; test nhận `actual` null/chưa sẵn sàng.
- Dùng `await` ngoài hàm `async` → lỗi biên dịch, test không build được.
- Không bắt lỗi Future → exception lan ra, test báo "Failed to load"/timeout.

### API/Widget chính
`Future<T>`, `async`, `await`, `Future.value`, `Future.delayed`, `Future.wait`, `Future.then`, `Future.catchError`

### Từ khóa
Future, async, await, asynchronous, network call, Future.wait

### Nguồn: Mastering Flutter, Ch.10, tr. 353–355

---

## skill_code: `async_event_loop`
**skill_name:** Event loop và microtask · **skill:** `async_programming`

### Khái niệm
Flutter chạy trên một event loop ở luồng chính, khởi động từ `main` qua `runApp`. Event loop xử lý sự kiện (tap, nhập liệu, vòng đời, hệ thống), rồi chạy các microtask (hàm ngắn ưu tiên cao, sau sự kiện hiện tại nhưng trước tác vụ bất đồng bộ khác), rồi rebuild UI. Mọi thứ làm chậm event loop đều khiến UI "đơ".

### Dấu hiệu đạt yêu cầu
- Không chạy tác vụ nặng/đồng bộ kéo dài trên luồng chính.
- Đẩy việc nặng sang `Future`/microtask/isolate để UI vẫn phản hồi.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Vòng lặp/tính toán nặng đồng bộ trên main isolate → test đo phản hồi UI bị fail/timeout.
- Hiểu sai thứ tự thực thi (đồng bộ → microtask → Future) → test kiểm tra thứ tự cho kết quả sai.

### API/Widget chính
`runApp`, `main`, `Future.microtask`, microtask queue

### Từ khóa
event loop, microtask, main isolate, runApp, responsive UI

### Nguồn: Mastering Flutter, Ch.10, tr. 355–356

---

## skill_code: `async_futurebuilder`
**skill_name:** FutureBuilder và AsyncSnapshot · **skill:** `async_programming`

### Khái niệm
`FutureBuilder` dựng UI theo trạng thái một `Future`; `builder` nhận `AsyncSnapshot` gồm `data`, `error`, `connectionState` (`none`, `waiting`, `active`, `done`). Builder trả widget khác nhau tùy trạng thái — loading khi chưa `done`, dữ liệu khi `done`.

### Dấu hiệu đạt yêu cầu
- Truyền đúng hàm trả về `Future` vào tham số `future`.
- Kiểm tra `connectionState` trước khi đọc `snapshot.data`.
- Có nhánh loading (`waiting`) và nhánh lỗi (`snapshot.hasError`).

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Đọc `snapshot.data` khi chưa `done` → test dữ liệu-sau-tải fail / hiển thị null.
- Không xử lý `waiting` → test tìm widget loading bị fail.
- Tạo `Future` ngay trong `build()` → gọi lại liên tục, UI nhấp nháy, test không ổn định.

### API/Widget chính
`FutureBuilder`, `AsyncSnapshot`, `ConnectionState`, `snapshot.data`, `snapshot.hasError`

### Từ khóa
FutureBuilder, AsyncSnapshot, connectionState, loading state, async UI

### Nguồn: Mastering Flutter, Ch.10, tr. 356–357

---

## skill_code: `async_streams_streambuilder`
**skill_name:** Streams và StreamBuilder · **skill:** `async_programming`

### Khái niệm
`Stream` là chuỗi sự kiện phát bất đồng bộ, có thể có listener. Hai loại: *single* (một listener) và *broadcast* (nhiều listener, qua `asBroadcastStream`). Tạo bằng `StreamController`, factory (`Stream.fromIterable`, `Stream.periodic`, `Stream.fromFuture`), hoặc hàm `async*` dùng `yield`. Tiêu thụ bằng `StreamBuilder` hoặc `listen()` (trả `StreamSubscription` có `cancel`/`pause`/`resume`).

### Dấu hiệu đạt yêu cầu
- Chọn đúng loại stream (single vs broadcast) theo số listener.
- Dùng `StreamBuilder` và kiểm tra `connectionState` trước khi đọc dữ liệu.
- Hủy `StreamSubscription` khi không dùng để tránh rò rỉ.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Lắng nghe nhiều lần trên single stream → lỗi "Stream has already been listened to".
- Không `cancel` subscription → rò rỉ tài nguyên.
- Đọc `snapshot.requireData` khi chưa có dữ liệu → test cập nhật-theo-sự-kiện bị fail.

### API/Widget chính
`Stream`, `StreamController`, `StreamBuilder`, `asBroadcastStream`, `Stream.periodic`, `async*`, `yield`, `StreamSubscription`

### Từ khóa
Stream, StreamBuilder, StreamController, broadcast, subscription, yield, async*

### Nguồn: Mastering Flutter, Ch.10, tr. 379–381

---

## skill_code: `async_isolates`
**skill_name:** Isolates và giao tiếp qua Port · **skill:** `async_programming`

### Khái niệm
Flutter chạy trong một isolate chính. Với tác vụ rất nặng (vd đồng bộ CSDL), dùng **isolate** để tạo luồng riêng. Mỗi isolate có heap và event loop riêng, không chia sẻ bộ nhớ; giao tiếp qua **Port**: gửi bằng `SendPort`, nhận bằng `ReceivePort`. Chỉ gửi được primitive, `List`, `Map`, `Set`, `SendPort`; dữ liệu phức tạp nên chuyển JSON.

### Dấu hiệu đạt yêu cầu
- Đưa tác vụ nặng sang isolate bằng `Isolate.spawn` thay vì chạy trên luồng chính.
- Giao tiếp đúng qua `SendPort`/`ReceivePort` và `listen`.
- Đóng isolate đúng cách (`isolate.kill()`); chỉ truyền kiểu dữ liệu được phép.

### Lỗi thường gặp → dấu hiệu trong kết quả chấm
- Cố chia sẻ biến/đối tượng giữa các isolate → lỗi runtime.
- Gửi kiểu dữ liệu không được hỗ trợ qua `SendPort` → exception.
- Quên đóng isolate/port → rò rỉ; test tác vụ-nền bị treo/fail.

### API/Widget chính
`Isolate.spawn`, `ReceivePort`, `SendPort`, `receivePort.listen`, `isolate.kill`, `dart:isolate`

### Từ khóa
isolate, SendPort, ReceivePort, Isolate.spawn, concurrency, background thread

### Nguồn: Mastering Flutter, Ch.10, tr. 394–396
