# Distributed Online Learning Platform

A microservices-based online learning platform built with **Java and Spring Cloud**, covering core workflows such as course management, learning progress, promotions, ordering and payments, search, reviews, and learning incentives.

The project is organized as a Maven multi-module system, with business capabilities separated into independently deployable services. Beyond the core learning workflows, the project focuses on concurrency control, asynchronous processing, Redis-based optimization, and distributed data processing.

## Tech Stack

**Backend**

* Java 11
* Spring Boot
* Spring Cloud
* MyBatis-Plus

**Data & Caching**

* MySQL
* Redis
* Redisson
* Elasticsearch

**Messaging & Distributed Processing**

* RabbitMQ
* XXL-JOB

**Infrastructure & DevOps**

* Docker
* Jenkins
* Nginx

## Project Structure

The platform is divided into domain-oriented microservices:

| Module         | Responsibility                                                |
| -------------- | ------------------------------------------------------------- |
| `tj-gateway`   | API gateway and request routing                               |
| `tj-auth`      | Authentication and authorization                              |
| `tj-user`      | User management                                               |
| `tj-course`    | Course management                                             |
| `tj-learning`  | Learning progress, learning records, points, and leaderboards |
| `tj-promotion` | Coupons and promotional workflows                             |
| `tj-trade`     | Order and transaction workflows                               |
| `tj-pay`       | Payment processing                                            |
| `tj-search`    | Course search                                                 |
| `tj-media`     | Media management                                              |
| `tj-exam`      | Examination workflows                                         |
| `tj-remark`    | Reviews and user interactions                                 |
| `tj-message`   | Messaging and notification support                            |
| `tj-data`      | Data-related services                                         |
| `tj-api`       | Shared service API definitions                                |
| `tj-common`    | Shared infrastructure and reusable components                 |

## Technical Highlights

### Learning Progress Optimization

> Reduced high-frequency database writes by retaining only the latest meaningful playback progress.

* Implemented **write coalescing with Redis** for high-frequency video progress updates. Since only the latest playback position is meaningful, intermediate updates continuously overwrite the cached value instead of being individually persisted to MySQL.
* Used a **Redisson delayed queue** to determine when progress should be persisted. Each delayed task compares its playback position with the latest value in Redis; stale tasks are discarded, while the latest progress is written to MySQL.
* Reduced MySQL write frequency and volume by approximately **95%**.

<img width="4391" height="3026" alt="3" src="https://github.com/user-attachments/assets/f833a35e-2c9e-4079-bb09-8f1dfa1c3b21" />

### High-Concurrency Coupon Claiming

> Redesigned the coupon-claiming path for atomic validation and asynchronous persistence under concurrent load.

* Built a reusable distributed locking framework with **Spring AOP, Redisson, and custom annotations**, using **Factory and Strategy patterns** to decouple concurrency control from business logic and support **4 lock types and 5 lock-acquisition strategies**.
* Redesigned the coupon-claiming workflow with **Redis and Lua**, moving stock validation and updates into an atomic Lua script to prevent concurrent requests from overselling coupon inventory.
* Used **RabbitMQ** to move database persistence out of the synchronous request path.
* Improved successful coupon allocation under concurrent load from **21/100 to 100/100** without overselling.

<img width="3179" height="2100" alt="5" src="https://github.com/user-attachments/assets/4fe1eff8-bc89-44f8-a914-9abb729e3dca" />

### Learning Points & Seasonal Leaderboard

> Combined asynchronous point processing, real-time ranking, and distributed historical persistence.

* Used **RabbitMQ** to asynchronously process point-earning events without blocking the original business flow.
* Maintained the current-season leaderboard with **Redis Sorted Sets** for real-time score updates and ranking queries.
* Stored historical leaderboards using **season-based MySQL table sharding**, with each season routed to a separate physical table.
* Used **distributed job scheduling with task sharding** to persist leaderboard data in parallel across service instances.
