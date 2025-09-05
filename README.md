# DDD Generator

A powerful Domain-Driven Design (DDD) code generator that creates well-structured Spring Boot applications following DDD principles, Clean Architecture, and CQRS patterns.

## 🚀 Features

- **Database-Driven Code Generation**: Analyzes your database schema to generate domain entities, repositories, and services
- **DDD Architecture**: Follows Domain-Driven Design principles with proper separation of concerns
- **Clean Architecture**: Implements hexagonal architecture with clear boundaries between layers
- **CQRS Pattern**: Generates Command and Query handlers for read/write separation
- **Multi-Module Maven Structure**: Creates modular Spring Boot projects
- **REST API Generation**: Auto-generates REST controllers with proper endpoints
- **Request/Response Testing**: Creates HTTP request files and Postman collections
- **Exception Handling**: Generates global exception handlers with proper error responses
- **Cross-Cutting Concerns**: Optional integration with logging, monitoring, and security libraries

## 📋 Prerequisites

- **Java 17 or higher**
- **Maven 3.6+**
- **Database**: PostgreSQL, MySQL, or any JDBC-compatible database
- **IDE**: IntelliJ IDEA, Eclipse, or VS Code (recommended: IntelliJ IDEA for HTTP client support)

## 🛠️ Installation & Setup

### 1. Clone the Repository
```bash
git clone https://github.com/muratagin/dddgenerator.git
cd dddgenerator
```

### 2. Build the Application
```bash
mvn clean install
```

### 3. Run the Application
```bash
mvn spring-boot:run
```

### 4. Access the Web Interface
Open your browser and navigate to:
```
http://localhost:8080
```

## 🎯 Quick Start Guide

### Step 1: Project Configuration
1. Open the DDD Generator web interface
2. Fill in the **Project Information**:
   - **Group ID**: `com.yourcompany`
   - **Artifact ID**: `your-project-name`
   - **Name**: `Your Project Name`
   - **Description**: Brief description of your project
   - **Package Name**: `com.yourcompany.yourproject`

### Step 2: Cross-Cutting Libraries (Optional)
Choose optional cross-cutting libraries:
- **Logging & Monitoring**: Structured logging and observability
- **Security**: Authentication and authorization
- **Caching**: Redis/Hazelcast integration
- **Message Queues**: RabbitMQ/Kafka integration

### Step 3: Environment Configuration
Configure your target environment:
- **Application Name**: Name for your Spring Boot application
- **Server Port**: Port number (default: 8080)
- **Banner Mode**: Spring Boot banner display mode

### Step 4: Database Connection
Provide your database connection details:
- **Database URL**: `jdbc:postgresql://localhost:5432/your_database`
- **Username**: Your database username
- **Password**: Your database password

### Step 5: Schema Selection
1. Select your database schema
2. Choose which tables represent **Aggregate Roots**:
   - ✅ **Aggregate Root**: Main business entities (e.g., `users`, `orders`, `products`)
   - ❌ **Entity**: Supporting entities (e.g., `order_items`, `addresses`)
   - ❌ **Value Object**: Value objects (e.g., `currencies`, `statuses`)

### Step 6: Generate Project
Click **"Generate Project"** to create your DDD-compliant Spring Boot application.

## 📁 Generated Project Structure

```
your-project-name/
├── your-project-name-container/          # Main application runner
│   └── src/main/java/
│       └── YourProjectApplication.java
├── your-project-name-domain/             # Domain layer
│   └── your-project-name-domain-core/    # Core domain logic
│       └── src/main/java/
│           ├── entity/                   # Domain entities
│           ├── valueobject/              # Value objects
│           └── exception/                # Domain exceptions
├── your-project-name-application-service/ # Application services
│   └── src/main/java/
│       ├── mapper/                       # Domain mappers
│       ├── ports/                        # Repository interfaces (ports)
│       ├── queries/                      # Query handlers (CQRS)
│       └── commands/                     # Command handlers (CQRS)
├── your-project-name-infrastructure/     # Infrastructure layer
│   ├── your-project-name-persistence/    # Data persistence
│   │   └── src/main/java/
│   │       ├── mapper/                   # Persistence mappers
│   │       ├── entity/                   # JPA entities
│   │       ├── adapter/                  # Repository implementations (adapters)
│   │       └── repository/               # JPA repositories
├── your-project-name-application/        # Application layer
│   └── src/main/java/
│       ├── rest/                         # REST controllers
│       ├── exception/                    # Global exception handlers
│       └── payload/                      # DTOs and responses
└── requests/                             # Testing utilities
    ├── http/                             # IntelliJ HTTP request files
    │   ├── users.http
    │   ├── orders.http
    │   └── products.http
    └── postman/                          # Postman collection
        └── your-project-name.postman_collection.json
```

## 🔧 Generated Components

### Domain Layer
- **Entities**: Rich domain models with business logic
- **Value Objects**: Immutable value types (enums, constants)
- **Domain Exceptions**: Custom business rule violations

### Application Service Layer
- **Command Handlers**: Process write operations (Create, Update, Delete)
- **Query Handlers**: Process read operations (Get, Search)
- **Repository Ports**: Domain contracts (interfaces) for data access
- **Domain Mappers**: Convert between domain and persistence models

### Infrastructure Layer
- **JPA Entities**: Database-mapped entities with proper annotations
- **Repository Adapters**: Repository implementations (adapters for domain ports)
- **JPA Repositories**: Spring Data JPA repositories
- **Persistence Mappers**: Convert between JPA and domain entities

### Application Layer (Web)
- **REST Controllers**: HTTP endpoints for aggregate roots
- **Global Exception Handler**: Centralized error handling
- **Result Objects**: Standardized API responses

### Testing Support
- **HTTP Files**: Ready-to-use request examples for IntelliJ IDEA (includes CRUD operations and pagination examples)
- **Postman Collection**: Complete API testing suite with organized folders for Commands and Queries

## 🌐 API Endpoints

For each aggregate root, the following REST endpoints are generated:

```http
# Create new entity
POST http://localhost:{port}/api/v1/{entities}
Content-Type: application/json

# Get entity by ID  
GET http://localhost:{port}/api/v1/{entities}/{id}

# Update entity
PUT http://localhost:{port}/api/v1/{entities}/{id}
Content-Type: application/json

# Delete entity
DELETE http://localhost:{port}/api/v1/{entities}/{id}

# Query entities (search with filters)
POST http://localhost:{port}/api/v1/{entities}/query
Content-Type: application/json
```

## 🧪 Testing Your Generated Application

### Using IntelliJ IDEA HTTP Client
1. Open the generated `requests/http/` folder
2. Open any `.http` file (e.g., `users.http`)
3. Each file contains:
   - **CRUD Operations**: Create, Read, Update, Delete
   - **Query Operations**: Search with filters
   - **Pagination Examples**: Different sorting and paging options
   - **Environment Variations**: Localhost and production examples
4. Click the green arrow next to any request to execute it
5. Modify request bodies as needed for your use case

### Using Postman
1. Import the generated Postman collection:
   - Open Postman
   - Click **Import** → **Upload Files**
   - Select `requests/postman/your-project-name.postman_collection.json`
2. Set up environment variables:
   - **baseUrl**: `http://localhost:8080` (or your configured port)
3. Execute requests and organize them into test suites

### Sample Request Bodies
The generator creates intelligent request bodies based on your database schema:

```json
// Create User Request
{
  "email": "user@example.com",
  "firstName": "John",
  "lastName": "Doe",
  "isActive": true,
  "createdAt": "2024-01-01T10:00:00"
}

// Query Users Request  
{
  "email": "john",
  "isActive": true,
  "createdAfter": "2024-01-01T00:00:00"
}
```

## ⚙️ Configuration

### Database Support
The generator supports various databases:
- **PostgreSQL**: `jdbc:postgresql://localhost:5432/database`
- **MySQL**: `jdbc:mysql://localhost:3306/database`
- **H2**: `jdbc:h2:mem:testdb`
- **Oracle**: `jdbc:oracle:thin:@localhost:1521:xe`

### Application Properties
Generated applications include environment-specific configurations:

```yaml
# application-local.yml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/your_database}
    username: ${DB_USER:your_username}
    password: ${DB_PASSWORD:your_password}
  
server:
  port: ${SERVER_PORT:8080}
```

## 🏗️ Architecture Principles

### Domain-Driven Design (DDD)
- **Aggregate Roots**: Entry points for business operations
- **Entities**: Objects with identity and lifecycle
- **Value Objects**: Immutable descriptive objects
- **Domain Services**: Business logic that doesn't belong to entities

### Clean Architecture
- **Domain Layer**: Pure business logic, no external dependencies
- **Application Layer**: Orchestrates domain operations
- **Infrastructure Layer**: External concerns (database, messaging)
- **Presentation Layer**: HTTP controllers and DTOs

### CQRS (Command Query Responsibility Segregation)
- **Commands**: Modify state, return success/failure
- **Queries**: Read data, return results
- **Handlers**: Process commands and queries separately

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/amazing-feature`
3. Commit your changes: `git commit -m 'Add amazing feature'`
4. Push to the branch: `git push origin feature/amazing-feature`
5. Open a Pull Request

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🆘 Support

### Common Issues

**Q: Database connection fails**
A: Verify your database is running and connection details are correct. Check firewall settings and network connectivity.

**Q: Generated code doesn't compile**
A: Ensure all required dependencies are in your Maven POM. Run `mvn clean install` to refresh dependencies.

**Q: HTTP requests return 404**
A: Verify the application is running on the correct port and endpoints match your configuration.

**Q: No aggregate roots detected**
A: Check your database schema and foreign key relationships. Ensure you've properly marked aggregate roots in the schema selection.

### Getting Help

- 📧 **Email**: support@dddgenerator.com
- 🐛 **Issues**: [GitHub Issues](https://github.com/muratagin/dddgenerator/issues)
- 📖 **Documentation**: [Wiki](https://github.com/muratagin/dddgenerator/wiki)
- 💬 **Discussions**: [GitHub Discussions](https://github.com/muratagin/dddgenerator/discussions)

---

**Happy Coding! 🚀**

*Generate clean, maintainable, and scalable applications with DDD Generator.*