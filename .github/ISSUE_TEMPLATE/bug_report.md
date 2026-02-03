---
name: Bug report
about: Create a report to help us improve
title: '[BUG] '
labels: bug
assignees: ''

---

**Describe the bug**
A clear and concise description of what the bug is.

**To Reproduce**
Steps to reproduce the behavior:
1. Configure sharding with '...'
2. Execute query '...'
3. See error

**Expected behavior**
A clear and concise description of what you expected to happen.

**Actual behavior**
What actually happened instead.

**Configuration**
```yaml
# Your sharding configuration
sharding:
  enabled: true
  # ... rest of config
```

**Code sample**
```java
// Minimal code sample that reproduces the issue
@Service
public class MyService {
    // ...
}
```

**Environment:**
 - Spring Boot version: [e.g. 3.2.0]
 - Java version: [e.g. 17]
 - Database: [e.g. PostgreSQL 15]
 - Sharding starter version: [e.g. 1.0.0]

**Stack trace**
```
If applicable, add the full stack trace here
```

**Additional context**
Add any other context about the problem here.