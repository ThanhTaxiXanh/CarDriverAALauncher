# CarDriverLauncher — dữ liệu tốc độ / biển báo / camera tại Việt Nam

## 1. Nguồn dữ liệu và độ tin cậy

**Không thể suy ra biển báo thật hoặc giới hạn tốc độ pháp lý từ hồ sơ BRouter `.brf`.**
Tệp `car-fast_1(2).brf` người dùng cung cấp là biến thể "MapCar" của BRouter:
`vmax=100`, `maxspeed_implicit` theo `highway` + `estimated_town_class`,
`maxspeed_surface`, `maxspeed_tracktype` là tham số định tuyến, ETA hoặc
khả năng chạy xe. `maxspeed_explicit`/ `maxspeed:forward`/ `maxspeed:backward`
được đánh giá trong `.brf`, nhưng toàn bộ giá trị **`min(...)` không được
xem là giới hạn tốc độ pháp lý.**

Nguồn cho giới hạn tốc độ **được gắn tag cụ thể** là OpenStreetMap:
- `maxspeed=*` — giới hạn từng đoạn đường nếu đã được gắn thẻ.
- `maxspeed:forward=*`, `maxspeed:backward=*` — theo chiều thứ tự nút của OSM way.
- `maxspeed:lanes:forward/backward=*` — chỉ dùng khi mọi làn cùng tốc độ.
  Nếu làn khác nhau mà không biết làn xe thực, hiển thị **"Làn: chưa rõ"**.
- `oneway` và `junction=roundabout` — chỉ dùng để loại bỏ chiều bị cấm.
- `traffic_sign`, `highway=speed_camera`, `enforcement=*` — các đối tượng
  thực sự được gắn điểm tọa độ, không tự dựng biển từ tốc độ tuyến.

Bất kỳ đoạn đường không có `maxspeed` minh bạch đều có giới hạn là **không rõ (—)**.
OSM do cộng đồng cập nhật, không đảm bảo đầy đủ/đúng hiện trạng và không thay biển thật.
`maxspeed:conditional`, tốc độ phụ thuộc giờ, loại xe/tải trọng/đô thị/pháp luật
không được tự diễn giải. Bản V1 này **chưa phải công cụ cảnh báo tốc độ được chứng nhận**.

## 2. Tạo cơ sở dữ liệu toàn Việt Nam bằng GitHub Actions

Nguồn PBF quốc gia: `https://download.geofabrik.de/asia/vietnam-latest.osm.pbf`.
Công cụ offline: `scripts/osm_traffic_extract.py` dựa trên pyosmium.

1. Mở **Actions → Build Vietnam Traffic OSM Data → Run workflow**.
2. Workflow tải PBF Việt Nam từ Geofabrik, phân tích các OSM ways đã có
   maxspeed và các nút có traffic_sign/camera/dịch vụ.
3. **Artifact:** `Vietnam-OSM-speed-sign-camera-regions` (ZIP).
4. Trên Android tải Artifact ZIP của GitHub Actions về điện thoại.
   **Không cần giải nén**: ứng dụng hỗ trợ trực tiếp cả tệp ZIP toàn quốc.
   Nếu chỉ cần một phần nhỏ, giải nén rồi chọn tệp `VN_*.json` khu vực.
   `index.json` chỉ là thống kê, không được nhập làm dữ liệu giao thông.
5. Trong ứng dụng, chọn **Nhập dữ liệu Việt Nam (ZIP hoặc JSON)**.
   Chọn Artifact ZIP để nhập tất cả vùng, hoặc một tệp VN_*.json.
   Nhập tập dữ liệu lớn trên điện thoại khi xe đỗ, cắm sạc. SQLite không xóa
   báo cáo tài xế đã lưu.
6. Bản đồ điện thoại và Android Auto hiển thị các điểm báo; HUD giới hạn tốc độ
   chỉ lấy kết quả match road OSM theo hướng và độ chính xác GPS.

Gói JSON không tự đồng bộ lên thiết bị; người dùng cần nhập tệp.
Ứng dụng không tạo dữ liệu giả khi vùng chưa được tải. Giới hạn nhập hiện tại
là 18 MiB mỗi gói. Cần giữ ít nhất vài trăm MB trống khi nhập nhiều gói.

Tự tạo trên máy tính (nếu có):
```bash
python -m pip install 'osmium>=4,<6'
python scripts/osm_traffic_extract.py --pbf vietnam-latest.osm.pbf --out data/traffic_vn
```

## 3. Cấu trúc cơ sở dữ liệu và lọc theo hướng

- `roads`: mỗi đoạn polyline con; `osm_way_id`, điểm A/B, bounding box,
  tốc độ chung và tốc độ theo chiều/làn, oneway, nguồn OSM.
- `events`: ID tọa độ điểm, loại (SPEED/SIGN/CAMERA/SERVICE/LANE), mã, tốc độ,
  `drivingBearing` (góc chiều xe đi), `lane`, `side`, nguồn, trạng thái.
- SQLite dùng index bounding-box trước khi tính khoảng cách vuông góc từ GPS
  đến đoạn đường và so góc di chuyển.
- Nếu GPS không đủ chính xác, không có hướng đáng tin, hoặc hai đường song song
  gần nhau không phân biệt được: **không gán giới hạn tốc độ**.
- Đóng góp địa phương `USER_PENDING` là điểm gần đường, **không được lấy làm
  tốc độ pháp lý** hay tự ghi đè dữ liệu OSM.
- Chọn lane LEFT/CENTER/RIGHT dựa trên quan sát của người báo, **không phải**
  định vị làn xe dựa trên GPS; cam chỉ hiện dưới dạng điểm chưa chứng thực.

Lưu ý riêng về `direction` trong OSM: với biển vật lý ngoài đường, hướng
mặt biển thường đối diện hướng xe tiếp cận. Không tự coi `direction=*` ở nút
là góc di chuyển; chỉ report của tài xế được hỏi trực tiếp **chiều xe đang đi**.
Trong lần trích xuất đầu tiên, hướng chưa thể kiểm chứng được để là `null`
thay vì đoán; muốn gán đường/hướng chính xác cần map matching + quan hệ
`enforcement` role `from`/`to` và kiểm duyệt.

## 4. Báo cáo & đóng góp theo GPS

Mở ứng dụng trên **điện thoại khi đỗ xe** → **Đóng góp thông tin giao thông**
→ loại báo cáo → tọa độ → tốc độ (nếu có) → làn → phía lắp biển →
hướng xe hiện tại/nhập tay/hai chiều/không rõ → **Lưu nháp**.

Danh mục gồm:
- Tốc độ: giới hạn và hết giới hạn.
- Biển báo: khu dân cư, cấm vượt, cấm rẽ, cấm quay đầu, cấm xe,
  cấm đỗ/dừng, giới hạn trọng lượng/kích thước, cảnh báo khác.
- Camera: tốc độ, đèn đỏ, phạt nguội, AI do người báo, khác.
- Dịch vụ: trạm nghỉ, trạm thu phí.
- Thông tin làn: hướng làn đi thẳng/rẽ, giới hạn phương tiện, khác.

Báo cáo chứa **tọa độ GPS chính xác, hướng và thời gian**; lưu ở thiết bị.
Nút **Xuất / chia sẻ báo cáo chưa xác minh** hỏi đồng ý rõ ràng rồi gửi
JSON qua bảng chia sẻ Android. Không có server xác minh/cộng đồng tự động
trong bản V1. Để triển khai đóng góp công khai cần backend có xác thực,
chống spam, kiểm chứng nhiều nguồn, lịch sử chỉnh sửa, phê duyệt/moderation
và xóa dữ liệu cá nhân khi được yêu cầu.

## 5. Bản quyền và trách nhiệm

© OpenStreetMap contributors, ODbL 1.0: https://www.openstreetmap.org/copyright
Tải nguồn qua © Geofabrik: https://download.geofabrik.de/asia/vietnam.html
Hồ sơ BRouter: https://github.com/abrensch/brouter

Dữ liệu OSM dẫn xuất khi phân phối có thể chịu các nghĩa vụ theo ODbL, bao gồm
ghi công và điều kiện chia sẻ tương tự khi áp dụng. Người dùng phải tuân thủ
biển báo thực tế và quy định đang có hiệu lực. Các trường `CAMERA`,
`LANE` chỉ là thông tin tham khảo, không phải chỉ dẫn an toàn xác thực.
