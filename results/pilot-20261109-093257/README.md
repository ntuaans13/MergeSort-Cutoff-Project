# Lượt chạy `pilot-20261109-093257`

Pilot chọn số vòng warm-up: mọi vòng (kể cả warm-up) đều được ghi lại.

**Vai trò:** Căn cứ chọn warm-up/đo/fork cho lưới chính ([báo cáo, mục 4.2](../../report/REPORT.md#42-pilot-chọn-warm-up)).

| Mục | Giá trị |
|---|---|
| Chế độ | `pilot` |
| Bắt đầu | 2026-11-09T09:33:01 |
| Backend | jmh (JMH 1.37) |
| JDK | OpenJDK 64-Bit Server VM 25.0.1 (Microsoft) |
| CPU | Intel Core i5-11400H (12 luồng) |
| Datasets | 4 |
| CUTOFF | `0,4,8,16,32` |
| Cấu hình được đo | 20 (70 JVM fork), trạng thái: 20 ok |
| Thời gian đo | ≈ 4 phút |
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
| [`jmh/`](jmh) | số liệu gốc định dạng JMH JSON, mỗi cấu hình một file (20 file) |
| [`logs/`](logs) | log JMH của từng cấu hình |

## Phân tích

Kết quả: [`analysis-pilot-20261109-093257/README.md`](../analysis-pilot-20261109-093257/README.md)

Tạo lại:

```powershell
.\scripts\analyze.ps1 results\pilot-20261109-093257
```
