# Atomic Operations vs Distributed Locks

## When Do We Need a Distributed Lock?

The confusing part is that there are **two different situations**:

1. **One operation that can be made atomic → Redis can handle it by itself.**
2. **Several operations that must stay together → sometimes we need a distributed lock.**

Let's use the shopping example.

### 1. One Atomic Operation: Check + Decrease Stock

Suppose Redis has:

```text
product:123 → stock = 1
```

Two clients arrive at the same time:

```text
Client A ──┐
           ├──→ Redis
Client B ──┘
```

We want:

> **"Decrease stock by 1 only if stock > 0."**

At first, this might look like Redis needs to perform two separate operations: first **check the stock** and then *
*decrease it**. If we actually did that, we would have a race condition:

```text
Request A              Request B
   ↓                      ↓
CHECK stock              CHECK stock
   ↓                      ↓
stock = 1                stock = 1
   ↓                      ↓
DECREASE                 DECREASE
   ↓                      ↓
stock = 0                stock = -1 ❌
```

A checks that stock is available, but before A decreases it, B can also check and see the same `stock = 1`. Now both
clients think they can buy the last product.

**So the concern is correct if "check stock" and "decrease stock" are separate operations.**

The important detail is that we don't actually make them separate operations. Instead, we combine them into **one atomic
operation** and tell Redis:

```text
"Decrease stock by 1 ONLY IF stock > 0"
```

Conceptually:

```text
IF stock > 0
    stock = stock - 1
    return SUCCESS
ELSE
    return SOLD_OUT
```

So Client A sends one operation:

```text
A → Redis: "buy 1"
             ↓
        check stock > 0
             ↓
        decrease stock
             ↓
        return success
```

Then Client B sends the same operation:

```text
B → Redis: "buy 1"
             ↓
        check stock > 0
             ↓
        stock = 0
             ↓
        return failure
```

Redis executes each **whole operation atomically**, meaning A's entire `check + decrease` finishes before B's operation
can run.

So with:

```text
stock = 1
```

the sequence is:

```text
        Redis
          │
          │ A: decrease if stock > 0
          ↓
       stock = 0
          │
          │ B: decrease if stock > 0
          ↓
       ❌ No stock
```

**B never gets the opportunity to see `stock = 1` after A has taken it.**

Think of it as one sentence.

Instead of asking Redis:

```text
"Is there stock?"
```

and then later asking:

```text
"Decrease stock."
```

we ask:

```text
"Decrease the stock IF there is stock available."
```

That entire instruction is **one atomic operation**.

Therefore, **no distributed lock is needed** for this particular stock check-and-decrease problem.

---

### 2. Multiple Operations: The Entire Purchase

Now consider a **multi-step purchase**.

Suppose buying a product requires:

```text
1. Check stock
2. Reserve stock
3. Hold stock
4. Call payment service
5. Create/update order
6. Update inventory
```

Now imagine:

```text
Client A
   ↓
Check stock → 1
   ↓
Reserve stock
   ↓
Payment
   ↓
Order
   ↓
Inventory
```

And at the same time:

```text
Client B
   ↓
Check stock → 1
```

Here's the problem.

A checks the stock:

```text
Stock = 1

A: "Is stock available?"
Redis: "Yes"
```

But **A hasn't finished buying yet**.

Now B checks:

```text
B: "Is stock available?"
Redis: "Yes"
```

Because the stock has not necessarily been changed yet, both A and B think:

```text
"I can buy the last product."
```

For the simple stock invariant, we solve this by making **check + decrease one atomic operation**.

However, the entire purchase is much larger than just that one operation. It involves **several operations that must
stay together**, such as checking stock, reserving stock, holding stock, calling a payment service, creating or updating
the order, and updating inventory.

These operations cannot necessarily be combined into one atomic Redis operation, especially when they involve *
*different services or systems**.

In such cases, if multiple application instances need to coordinate access to the same **critical section**, a *
*distributed lock may be needed**.

---

## The Key Distinction

The key distinction is:

```text
One atomic operation
        ↓
Redis can handle it
        ↓
No additional distributed lock needed
```

versus:

```text
Multiple operations
        ↓
Must stay together
        ↓
Cannot be made into one atomic operation
        ↓
A distributed lock may be needed
```

So don't think:

> **"Two simultaneous requests → we need a distributed lock."**

Instead, ask:

> **"Can I make the entire check-and-update operation atomic?"**

If the answer is **yes**, an atomic operation is usually enough.

If the answer is **no**, and multiple instances must coordinate a **multi-step critical section**, then a distributed
lock may be one possible solution.

# Why Lettuce Is Enough for Redis-Based Rate Limiting

## Overview

The application runs as a **multiple-instance backend application**. Multiple backend instances need to enforce the same
rate limit, so the rate-limit state cannot be stored only in application memory.

Instead, all backend instances share the same **Redis database**.

```text
                    Load Balancer
                   /             \
                  ↓               ↓
            Backend A        Backend B
                  \               /
                   \             /
                       Redis
```

Redis therefore acts as the **central shared state store** for the rate limiter.

The main question is:

> **If multiple backend instances share Redis, why don't we need a distributed lock, and why do we choose Lettuce
instead of Redisson?**

---

## 1. Why Redis Is Needed

With a single backend instance, the rate-limit bucket could be stored in application memory.

```text
Backend
   ↓
Application Memory
   ↓
Bucket
```

However, this does not work correctly with multiple instances.

For example:

```text
Backend A → Bucket A
Backend B → Bucket B
```

Each instance would have a different bucket.

A client could therefore send requests through different instances and effectively get a separate rate limit from each
one.

Instead, the bucket state is stored in **Redis**:

```text
Backend A ──┐
Backend B ──┼──→ Redis
Backend C ──┘
```

Now every backend instance accesses the **same bucket state**.

---

## 2. Multiple Instances Create Concurrent Access

Multiple requests can arrive at the same time:

```text
Request A → Backend A ──┐
                       ├──→ Redis
Request B → Backend B ──┘
```

Both requests may try to modify the same rate-limit bucket.

A naive implementation might perform:

```text
1. Read bucket
2. Check whether a token exists
3. Consume the token
4. Save the bucket
```

If these operations are separate, two requests could read the same bucket state before either request updates it.

This creates a **race condition**.

---

## 3. Redis Atomic Operations

The solution is to make the required bucket update an **atomic operation**.

An atomic operation means:

> **The entire operation is treated as one indivisible action.**

For rate limiting, the operation is conceptually:

```text
Check whether a token is available
              +
       Consume the token
              ↓
     ONE atomic operation
```

Redis executes commands atomically with respect to other Redis commands.

Therefore, another request cannot modify the relevant Redis state **in the middle of the atomic operation**.

This means concurrent requests can safely access the same bucket.

Important:

> **Concurrency still exists in the application.** Multiple requests can execute at the same time. What Redis prevents
> is a race condition inside the atomic bucket update.

---

## 4. Lua Scripts

A **Lua script** is a small piece of code that can be sent to Redis and executed by Redis.

Lua is useful when the operation requires multiple Redis commands but those commands need to behave as **one atomic
operation**.

For example:

```lua
local tokens = redis.call("GET", KEYS[1])

if tonumber(tokens) > 0 then
    redis.call("DECR", KEYS[1])
    return 1
else
    return 0
end
```

The script performs:

```text
GET token count
      ↓
Check token
      ↓
Token available?
   /        \
 YES         NO
 ↓            ↓
DECR       Reject
 ↓
Success
```

Although the script contains multiple Redis commands (`GET` and `DECR`), Redis executes the **entire Lua script as one
atomic operation**.

Another Redis operation cannot execute between the `GET` and `DECR` inside that script.

Therefore:

```text
Request A ──→ Redis
                ↓
        Execute Lua script
                ↓
        Check + consume token
                ↓
             Success
```

At the same time:

```text
Request B ──→ Redis
                ↓
        Waits until the script
        finishes
                ↓
        Sees the updated state
                ↓
          Reject if necessary
```

---

## 5. Why a Distributed Lock Is Not Required

A **distributed lock** has a different purpose.

It means:

> **Only one thread, across all application instances, can enter a particular critical section at a time.**

For example:

```text
🔒 Acquire lock

    Check
    Reserve
    Update
    ...

🔓 Release lock
```

For rate limiting, we do not need to lock the entire workflow.

We only need:

```text
Check token
     +
Consume token
     ↓
Atomic Redis operation
```

Since Redis already executes this operation atomically, an additional distributed lock is unnecessary.

The flow becomes:

```text
Multiple Backend Instances
            ↓
       Shared Redis
            ↓
       Lua Script
            ↓
   Atomic Execution
            ↓
   Allow / Reject Request
```

---

## 6. Why We Choose Lettuce

The application needs a **Java Redis client** to communicate with Redis.

We use **Lettuce** for this purpose.

```text
Bucket4j
    ↓
  Lettuce
    ↓
   Redis
    ↓
Lua Script
    ↓
Atomic Bucket Update
```

Lettuce sends the Redis operation, including the **Lua script**, to Redis.

Redis then executes the Lua script as **one single atomic operation**.

So the important sequence is:

```text
Lettuce
   ↓
Sends Redis command / Lua script
   ↓
Redis
   ↓
Executes the Lua script atomically
   ↓
Bucket state is safely updated
```

Therefore, we do **not** need Lettuce itself to provide a distributed lock.

The client only needs to communicate with Redis and send the required operation.

---

## 7. Why Not Redisson?

**Redisson** is another Java Redis client/framework.

One of its useful features is a high-level **distributed lock** abstraction.

Conceptually:

```text
Application
     ↓
 Redisson
     ↓
Distributed Lock
     ↓
  Redis
```

However, our rate limiter does not require that lock.

We already have:

```text
Application
     ↓
  Lettuce
     ↓
Lua Script
     ↓
Redis
     ↓
Atomic Operation
```

The atomic Redis operation already protects the bucket update.

Adding a distributed lock would therefore introduce unnecessary complexity:

```text
Acquire Lock
     ↓
Execute Operation
     ↓
Release Lock
```

Instead, we can simply perform:

```text
Send Lua Script
     ↓
Redis executes it atomically
     ↓
Done
```

---

## 8. Important: Atomicity Comes From Redis

It is important to understand the responsibility of each component.

| Component      | Responsibility                                                      |
|----------------|---------------------------------------------------------------------|
| **Bucket4j**   | Provides the rate-limiting logic                                    |
| **Lettuce**    | Java client that communicates with Redis                            |
| **Redis**      | Stores the shared bucket state and provides atomic execution        |
| **Lua script** | Combines multiple Redis commands into one atomic operation          |
| **Redisson**   | Provides additional Redis abstractions, including distributed locks |

The **atomicity does not come from Lettuce**.

Lettuce simply sends the operation to Redis.

The important part is:

> **Redis executes the Lua script atomically as one operation.**

---

## 9. Why a Distributed Lock Would Be Overkill

Without a distributed lock:

```text
Request
   ↓
Lettuce
   ↓
Redis
   ↓
Lua Script
   ↓
Atomic bucket update
   ↓
Allow / Reject
```

With a distributed lock:

```text
Request
   ↓
Acquire distributed lock
   ↓
Check bucket
   ↓
Update bucket
   ↓
Release distributed lock
```

The second approach introduces additional concerns:

* Lock acquisition
* Lock release
* Waiting for the lock
* Lock expiration
* Failure handling
* Additional Redis communication
* More application complexity

If Redis can already perform the required operation atomically, the additional distributed lock provides no useful
protection for this rate-limiting operation.

---

## 10. The Key Principle

The most important idea is:

> **Multiple application instances require shared state, but shared state does not automatically require a distributed
lock.**

Our architecture is:

```text
Multiple Backend Instances
            ↓
     Shared Redis State
            ↓
         Lettuce
            ↓
      Lua Script Sent
            ↓
    Redis Executes Script
            ↓
    One Atomic Operation
            ↓
    Safe Bucket Update
            ↓
     No Distributed Lock
```

### Final Decision

**Redis** is used because all backend instances need access to the **same rate-limit state**.

**Lettuce** is chosen as the simple Java Redis client used to communicate with Redis.

**Lua scripts** allow the required multi-step Redis operation, such as **check + consume**, to be sent to Redis as a
single script.

**Redis executes that Lua script atomically**, so concurrent requests cannot interfere with the bucket update.

Therefore, **Redisson's distributed-lock functionality is not required** for this rate-limiting use case.

The core principle is:

> **Instead of locking the operation, make the operation atomic.**
