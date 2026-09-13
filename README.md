### Learning Progress Optimization

- Implemented **write coalescing with Redis** for high-frequency video progress updates. Since only the latest playback position is meaningful, intermediate updates continuously overwrite the cached value instead of being individually persisted to MySQL.
- Used a **Redisson delayed queue** to determine when progress should be persisted. Each delayed task compares its playback position with the latest value in Redis; stale tasks are discarded, while the latest progress is written to MySQL.
- Reduced MySQL write frequency and volume by approximately **95%** by persisting only the latest meaningful playback progress.

<img width="4391" height="3026" alt="3" src="https://github.com/user-attachments/assets/f833a35e-2c9e-4079-bb09-8f1dfa1c3b21" />
