# Proyecto Sistemas Distribuidos
## Sistema de Asignación de Recursos Universitarios

Este proyecto implementa un sistema distribuido de asignación de recursos universitarios (aulas y laboratorios) que facilita la distribución de recursos académicos a través de una arquitectura tolerante a fallos basada en mensajería usando ZeroMQ.

### 🏗️ Arquitectura del Sistema

El sistema implementa una arquitectura distribuida multi-nivel donde los Programas Académicos solicitan recursos a través de Escuelas de Departamento, que enrutan las solicitudes mediante proxies con monitoreo de salud hacia servidores de asignación de recursos respaldados por una base de datos MySQL.

#### Componentes Principales

| Componente | Clase Principal | Responsabilidades | Patrón de Comunicación |
|------------|-----------------|-------------------|------------------------|
| **Academic Program** | `AcademicProgram.main()` | Generar solicitudes de recursos | Cliente REQ socket |
| **Department School** | `DepartmentSchool.main()` | Enriquecer solicitudes con contexto de facultad | Servidor ROUTER/REP + Cliente DEALER/REQ |
| **Health Check Manager** | `HealthCheckManager.main()` | Coordinación de failover y monitoreo de salud | Proxy ROUTER/DEALER |
| **Central Server** | `CentralServer.main()` | Asignación de recursos y operaciones de base de datos | Responder DEALER/REP |
| **Backup Server** | `BackupCentralServer.main()` | Servidor de respaldo para tolerancia a fallos | Responder DEALER/REP |

### 🚀 Tecnologías Utilizadas

- **Java** - Lenguaje principal de desarrollo
- **Maven** - Gestión de dependencias y construcción
- **ZeroMQ** - Framework de mensajería para comunicación distribuida
- **MySQL** - Base de datos para persistencia de recursos
- **Patrones de comunicación ZMQ:**
  - REQ/REP para comunicación síncrona
  - ROUTER/DEALER para enrutamiento asíncrono de mensajes
  - Polling para monitoreo no bloqueante de sockets

### 📋 Prerrequisitos

- Java JDK 8 o superior
- Maven 3.6 o superior
- MySQL Server
- ZeroMQ libraries

### 🛠️ Compilación

Para compilar el proyecto, ejecuta los siguientes comandos en el directorio raíz:

```bash
# Limpiar, compilar y empaquetar el proyecto
mvn clean compile package
```

### ⚡ Ejecución

El sistema debe ejecutarse en el siguiente orden para garantizar el correcto funcionamiento:

#### 1. Servidor de Respaldo (Backup)
```bash
mvn exec:java -Dexec.mainClass="com.backupserver.BackupCentralServer"
```

#### 2. Servidor Central
```bash
mvn exec:java -Dexec.mainClass="com.example.ServidorCentral"
```

#### 3. Gestor de Verificación de Salud
```bash
mvn exec:java -Dexec.mainClass="com.healthcheck.HealthCheckManager"
```

#### 4. Escuela de Departamento/Facultad
```bash
mvn exec:java -Dexec.mainClass="com.departmentschool.DepartmentSchool" -Dexec.args="'Facultad de Ingenieria' 2025-10"
```

#### 5. Programa Académico
```bash
mvn exec:java -Dexec.mainClass="com.academicprogram.AcademicProgram" -Dexec.args="'Ingenieria de Sistemas' 2025-10 7 2 10.43.103.241 5554"
```

### 🔧 Parámetros de Ejecución

#### Academic Program
- `Argumento 1`: Nombre del programa académico (ej: 'Ingenieria de Sistemas')
- `Argumento 2`: Período académico (ej: 2025-10)
- `Argumento 3`: Número de aulas necesarias
- `Argumento 4`: Número de laboratorios necesarios
- `Argumento 5`: Dirección IP del servidor
- `Argumento 6`: Puerto de conexión

#### Department School
- `Argumento 1`: Nombre de la facultad (ej: 'Facultad de Ingenieria')
- `Argumento 2`: Período académico (ej: 2025-10)

### 🔄 Tolerancia a Fallos

El sistema implementa un mecanismo automático de failover a través del `HealthCheckManager`:

- Envía mensajes "PING" periódicos y espera respuestas "PONG"
- Cuando el servidor primario falla en responder dentro del tiempo límite (`TIMEOUT_MS`), automáticamente cambia la conexión del socket backend del `PRIMARY_SERVER` al `BACKUP_SERVER`
- Garantiza la continuidad del servicio sin intervención manual

### 📊 Flujo de Trabajo

1. **Inicio del Sistema**: Los servidores de respaldo y central se inician primero
2. **Monitoreo de Salud**: El HealthCheckManager supervisa la salud de los servidores
3. **Registro de Facultades**: Las escuelas de departamento se registran en el sistema
4. **Solicitudes de Recursos**: Los programas académicos solicitan recursos específicos
5. **Procesamiento**: Las solicitudes se enrican con contexto de facultad y se procesan
6. **Asignación**: Los recursos se asignan basándose en disponibilidad y políticas
7. **Respuesta**: Se confirma la asignación o se notifica la indisponibilidad

### 🔍 Monitoreo y Debugging

Para verificar el estado del sistema, el `HealthCheckManager` proporciona logs detallados sobre:
- Estado de conexión de servidores
- Cambios de failover
- Tiempos de respuesta
- Errores de comunicación

### 📝 Notas Importantes

- Asegúrate de que MySQL esté en ejecución antes de iniciar los servidores
- Los puertos utilizados deben estar disponibles en el sistema
- Mantén el orden de ejecución para evitar errores de conexión
- El sistema soporta múltiples programas académicos ejecutándose simultáneamente

### 🤝 Contribución

Para contribuir al proyecto:
1. Fork el repositorio
2. Crea una branch para tu feature
3. Commit tus cambios
4. Push a la branch
5. Crea un Pull Request

---

Para más información detallada sobre patrones de implementación y componentes específicos, consulta la [documentación completa del proyecto](https://deepwiki.com/david-rodj/Proyecto-Sistemas-Distribuidos/1-overview).