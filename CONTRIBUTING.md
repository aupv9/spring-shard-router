# Contributing to Spring Boot Starter Sharding JDBC

We love your input! We want to make contributing to this project as easy and transparent as possible, whether it's:

- Reporting a bug
- Discussing the current state of the code
- Submitting a fix
- Proposing new features
- Becoming a maintainer

## Development Process

We use GitHub to host code, to track issues and feature requests, as well as accept pull requests.

## Pull Requests

Pull requests are the best way to propose changes to the codebase. We actively welcome your pull requests:

1. Fork the repo and create your branch from `main`.
2. If you've added code that should be tested, add tests.
3. If you've changed APIs, update the documentation.
4. Ensure the test suite passes.
5. Make sure your code lints.
6. Issue that pull request!

## Any contributions you make will be under the MIT Software License

In short, when you submit code changes, your submissions are understood to be under the same [MIT License](http://choosealicense.com/licenses/mit/) that covers the project. Feel free to contact the maintainers if that's a concern.

## Report bugs using GitHub's [issue tracker](https://github.com/your-username/spring-boot-starter-sharding-jdbc/issues)

We use GitHub issues to track public bugs. Report a bug by [opening a new issue](https://github.com/your-username/spring-boot-starter-sharding-jdbc/issues/new); it's that easy!

## Write bug reports with detail, background, and sample code

**Great Bug Reports** tend to have:

- A quick summary and/or background
- Steps to reproduce
  - Be specific!
  - Give sample code if you can
- What you expected would happen
- What actually happens
- Notes (possibly including why you think this might be happening, or stuff you tried that didn't work)

## Development Setup

1. Clone the repository
```bash
git clone https://github.com/your-username/spring-boot-starter-sharding-jdbc.git
cd spring-boot-starter-sharding-jdbc
```

2. Build the project
```bash
./mvnw clean install
```

3. Run tests
```bash
./mvnw test
```

4. Run integration tests (requires Docker)
```bash
./mvnw test -f example-app/pom.xml
```

## Code Style

- Follow standard Java conventions
- Use meaningful variable and method names
- Add JavaDoc comments for public APIs
- Keep methods focused and small
- Write tests for new functionality

## Testing

- Unit tests for core logic
- Integration tests with Testcontainers for database operations
- Performance tests for scalability validation
- All tests should be deterministic and fast

## License

By contributing, you agree that your contributions will be licensed under its MIT License.