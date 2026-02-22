# ImmutableLog Spring Boot Filter

Filtro Spring Boot que captura automaticamente todas as requisições HTTP — erros, sucessos e exceções não tratadas — e envia eventos de auditoria imutáveis para o ImmutableLog.

Usa `OncePerRequestFilter` + `HttpClient.sendAsync()` (Java 11+). Fire-and-forget — nunca bloqueia a resposta ao cliente.

---

## Instalação

O filtro usa apenas bibliotecas incluídas no `spring-boot-starter-web`. Para AOP, adicione:

**Maven**
```xml
<!-- pom.xml -->
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
</dependency>
```

**Gradle**
```groovy
// build.gradle
implementation 'com.fasterxml.jackson.core:jackson-databind'
```

> Jackson já vem incluso no `spring-boot-starter-web`. Para AOP, adicione `spring-boot-starter-aop`.

---

## Adicionar ao projeto

Copie `ImmutableLogFilter.java` para dentro do seu projeto:

```
src/main/java/com/example/filter/ImmutableLogFilter.java
src/main/java/com/example/config/ImmutableLogFilter.java
```

O Spring Boot detecta o `@Component` e registra o filtro automaticamente — sem nenhuma configuração extra.

---

## Variáveis de ambiente

| Variável             | Obrigatório | Default                        | Descrição                              |
|----------------------|-------------|--------------------------------|----------------------------------------|
| `IMTBL_API_KEY`      | Sim         | —                              | Chave de API do ImmutableLog           |
| `IMTBL_SERVICE_NAME` | Não         | `my-service`                   | Nome do serviço exibido nos eventos    |
| `IMTBL_ENV`          | Não         | `production`                   | Ambiente: `production`, `staging`, `development` |

> **Segurança:** nunca exponha `IMTBL_API_KEY` em código ou repositórios. Use sempre variáveis de ambiente.

---

## Configuração

**application.properties**
```properties
immutablelog.api-key=${IMTBL_API_KEY}
immutablelog.service-name=${IMTBL_SERVICE_NAME:my-service}
immutablelog.env=${IMTBL_ENV:production}
immutablelog.url=https://api.immutablelog.com
```

**application.yml**
```yaml
immutablelog:
  api-key: ${IMTBL_API_KEY}
  service-name: ${IMTBL_SERVICE_NAME:my-service}
  env: ${IMTBL_ENV:production}
  url: https://api.immutablelog.com
```

---

## Como funciona

| Etapa                       | Descrição                                                                 |
|-----------------------------|---------------------------------------------------------------------------|
| Wrapping da requisição      | `ContentCachingRequestWrapper` e `ResponseWrapper` para leitura do body   |
| `chain.doFilter()`          | Requisição flui normalmente para o controller                             |
| `finally` + `sendAsync()`   | Evento montado e enviado de forma assíncrona após a resposta              |
| Hash do body                | SHA-256 do body — nunca o conteúdo bruto é enviado ao ImmutableLog        |

---

## Nome de evento customizado

Por padrão o evento é nomeado `method.path` (ex: `post.payments`). Para sobrescrever:

```java
@RestController
@RequestMapping("/payments")
public class PaymentController {

    @PostMapping
    public ResponseEntity<?> create(HttpServletRequest request, @RequestBody PaymentDto dto) {
        request.setAttribute("imtbl.eventName", "payment.created");
        // ... lógica de negócio ...
        return ResponseEntity.ok(result);
    }
}
```

---

## Exclusão de paths

```java
private static final Set<String> SKIP_PATHS = Set.of(
    "/health", "/actuator/health", "/actuator/info", "/ping"
);

@Override
protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return SKIP_PATHS.stream().anyMatch(path::startsWith);
}
```

---

## Payload enviado

```json
{
  "payload": "{\"method\":\"POST\",\"path\":\"/payments\",\"status\":200,\"latency_ms\":28,\"client_ip\":\"1.2.3.4\",\"user_agent\":\"...\",\"request_body_hash\":\"sha256...\"}",
  "meta": {
    "type": "success",
    "event_name": "payment.created",
    "service": "my-service",
    "request_id": "uuid",
    "env": "production"
  }
}
```

> O campo `payload` é uma **string JSON serializada** — não um objeto.

---

## Comportamento

| Situação                           | Resultado                                               |
|------------------------------------|---------------------------------------------------------|
| `apiKey` vazio                     | Filtro registrado, mas evento não enviado               |
| Path em `SKIP_PATHS`               | Evento ignorado, requisição prossegue normalmente       |
| Status 2xx                         | `type: success`                                         |
| Status 3xx                         | `type: info`                                            |
| Status 4xx / 5xx                   | `type: error`                                           |
| Exceção não tratada                | `type: error` com `exception.type` e `exception.message` |
| Falha no `sendAsync`               | Exceção silenciada — nunca quebra a aplicação           |
