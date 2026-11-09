# Kết quả đo

Tất cả lượt chạy được đo trên cùng một máy (Intel Core i5-11400H, Windows 11, Microsoft OpenJDK 25.0.1, JMH 1.37),
với cùng một cài đặt `MergeSort` (SHA-256 của `MergeSort.class`: `53f5e31b54fecf61…`, ghi trong `environment.txt`
của từng lượt). Số liệu gốc (JSON định dạng JMH, log) được giữ nguyên, không sửa tay.

Mỗi thư mục đều có `README.md` riêng: thư mục lượt chạy mô tả cấu hình và các file số liệu gốc; thư mục
`analysis-…` chứa bảng, hình và thống kê (sinh bởi `scripts/analyze.py`).

## Lượt chạy dùng trong báo cáo

| Thư mục | Vai trò | Quy mô |
|---|---|---|
| [`pilot-20261109-093257`](pilot-20261109-093257) | Pilot chọn warm-up (máy rảnh) | 4 datasets × 5 CUTOFF, 70 JVM |
| [`grid-20261109-095145`](grid-20261109-095145) | **Lưới chính** | 51 datasets × CUTOFF 0–64, 646 cấu hình, 2.346 JVM, ≈ 75 phút đo |
| [`doubling-20261109-112935`](doubling-20261109-112935) | Doubling test | N = 2¹²…2²⁰ × CUTOFF {0, 7, 10, 20}, 124 JVM |
| [`analysis-grid-20261109-095145`](analysis-grid-20261109-095145/README.md) | Phân tích lưới chính + doubling | bảng, CSV, hình |
| [`analysis-pilot-20261109-093257`](analysis-pilot-20261109-093257/README.md) | Phân tích pilot | bảng, hình |
| `best_cutoff.txt` | CUTOFF\* = 20, đầu vào của doubling test | |

Pilot đầu tiên và lượt doubling đầu tiên (đo khi máy đang được dùng / thiếu warm-up) đã bị thay thế bởi các lượt
trên và không được lưu trong repo; lý do được trình bày ở mục 7 của [báo cáo](../report/REPORT.md).

## Tái tạo phần phân tích

```powershell
.\scripts\analyze.ps1 results\grid-20261109-095145 --doubling results\doubling-20261109-112935
.\scripts\analyze.ps1 results\pilot-20261109-093257
```
