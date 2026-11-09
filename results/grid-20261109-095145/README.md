# Lượt chạy `grid-20261109-095145`

Lưới chính: thời gian sort theo CUTOFF 0–64 trên 51 bộ dữ liệu.

**Vai trò:** Số liệu cho toàn bộ kết luận về CUTOFF ([báo cáo, mục 5–6](../../report/REPORT.md#5-kết-quả)).

| Mục | Giá trị |
|---|---|
| Chế độ | `grid` |
| Bắt đầu | 2026-11-09T09:53:38 |
| Backend | jmh (JMH 1.37) |
| JDK | OpenJDK 64-Bit Server VM 25.0.1 (Microsoft) |
| CPU | Intel Core i5-11400H (12 luồng) |
| Datasets | 51 |
| CUTOFF | `0-64` (65 giá trị, mặc định của Launcher) |
| Cấu hình được đo | 646 (2346 JVM fork), trạng thái: 646 ok |
| Thời gian đo | ≈ 75 phút |
| warm-up / đo / fork | 20 / 10 / 3; theo N: `forks.10000=5`, `measurements.10000=30`, `warmup.10000=100` |
| Tham số JVM | `-Xms2g -Xmx2g -XX:+UseParallelGC` |
| SHA-256 `MergeSort.class` | `53f5e31b54fecf61…` |

## Nội dung

| File | Nội dung |
|---|---|
| [`config.properties`](config.properties) | cấu hình hiệu lực của lượt chạy |
| [`environment.txt`](environment.txt) | JDK, OS, CPU, tham số JVM, SHA-256 của cài đặt |
| [`manifest.csv`](manifest.csv) | bản sao manifest dữ liệu (SHA-256 từng dataset) |
| [`schedule.csv`](schedule.csv) | thứ tự chạy (đã xáo trộn), trạng thái, thời gian từng cấu hình |
| [`compares.csv`](compares.csv) | số phép so sánh chính xác theo (dataset, CUTOFF) |
| [`equivalence.csv`](equivalence.csv) | lớp CUTOFF tương đương (cùng cây đệ quy) theo N |
| [`jmh/`](jmh) | số liệu gốc định dạng JMH JSON, mỗi cấu hình một file (646 file) |
| [`logs/`](logs) | log JMH của từng cấu hình |

## Phân tích

Kết quả: [`analysis-grid-20261109-095145/README.md`](../analysis-grid-20261109-095145/README.md)

Tạo lại:

```powershell
.\scripts\analyze.ps1 results\grid-20261109-095145 --doubling results\doubling-20261109-112935
```
