# Lượt chạy `doubling-20261109-112935`

Doubling test: random, N = 2¹²…2²⁰, CUTOFF {0, 7, 10, 20}.

**Vai trò:** Kiểm tra bậc tăng ([báo cáo, mục 6.6](../../report/REPORT.md#66-doubling-test-n-lg-n-cộng-ảnh-hưởng-của-bộ-nhớ)); phân tích nằm chung với lưới chính.

| Mục | Giá trị |
|---|---|
| Chế độ | `doubling` |
| Bắt đầu | 2026-11-09T11:29:46 |
| Backend | jmh (JMH 1.37) |
| JDK | OpenJDK 64-Bit Server VM 25.0.1 (Microsoft) |
| CPU | Intel Core i5-11400H (12 luồng) |
| Datasets | 9 |
| CUTOFF | `0,7,10,best` → `0,7,10,20` |
| Cấu hình được đo | 36 (124 JVM fork), trạng thái: 36 ok |
| Thời gian đo | ≈ 4 phút |
| warm-up / đo / fork | 20 / 10 / 3; theo N: `forks.4096=5`, `forks.8192=5`, `measurements.16384=30`, `measurements.4096=30`, `measurements.8192=30`, `warmup.16384=100`, `warmup.32768=50`, `warmup.4096=300`, `warmup.8192=200` |
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
| [`jmh/`](jmh) | số liệu gốc định dạng JMH JSON, mỗi cấu hình một file (36 file) |
| [`logs/`](logs) | log JMH của từng cấu hình |

## Phân tích

Kết quả: [`analysis-grid-20261109-095145/README.md`](../analysis-grid-20261109-095145/README.md)

Tạo lại:

```powershell
.\scripts\analyze.ps1 results\grid-20261109-095145 --doubling results\doubling-20261109-112935
```
