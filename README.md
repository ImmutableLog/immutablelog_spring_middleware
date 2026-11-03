# ImmutableLog Spring Boot Filter

Filtro Spring Boot que captura automaticamente todas as requisições HTTP — erros, sucessos e exceções não tratadas —, exceto `/health`, `/actuator/health` e `/ping` (ver [Exclusão de paths](#exclusão-de-paths)), e envia eventos de auditoria imutáveis para o ImmutableLog.

Usa `OncePerRequestFilter` + `HttpClient.sendAsync()`. Fire-and-forget — o envio ao ImmutableLog não espera resposta (ver [Limitações conhecidas](#limitações-conhecidas)).

**Requisitos:** Spring Boot 3 / Spring Framework 6 — o filtro importa `jakarta.servlet` (não `javax.servlet`), e o Spring Boot 3 exige Java 17+.

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

Copie `ImmutableLogFilter.java` para dentro do seu projeto, por exemplo:

```
src/main/java/com/example/filter/ImmutableLogFilter.java
```

O arquivo **não tem declaração `package`** (ele começa direto nos `import`). Ao copiar, acrescente na primeira linha o pacote da pasta onde ele ficou:

```java
package com.example.filter;
```

O Spring Boot detecta o `@Component` e registra o filtro sem configuração extra **desde que o pacote esteja sob o component scan da aplicação** — por padrão, o pacote da classe anotada com `@SpringBootApplication` e os subpacotes dele. Uma classe no pacote default (sem `package`) não é alcançada pelo component scan padrão.

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
| `finally` + `sendAsync()`   | Depois do `chain.doFilter()`, o evento é montado na thread da requisição e enviado com `sendAsync()`; só então o body da resposta é copiado para o cliente |
| Hash do body                | SHA-256 do body, quando houver bytes em cache (só o que a aplicação leu) — nunca o conteúdo bruto é enviado ao ImmutableLog |

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

O filtro já vem com esta lista fixa no código:

```java
private static final Set<String> SKIP_PATHS = Set.of(
    "/health", "/actuator/health", "/ping"
);

@Override
protected boolean shouldNotFilter(HttpServletRequest request) {
    return SKIP_PATHS.contains(request.getRequestURI());
}
```

A comparação é **exata** com `request.getRequestURI()`, não por prefixo: `/actuator/health/liveness`, `/actuator/info` e `/health/` (com barra no fim) são auditados normalmente. Para mudar a lista, edite `SKIP_PATHS` no arquivo copiado.

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
    "env": "production",
    "environment": "production",
    "client_ip": "1.2.3.4",
    "http_method": "POST",
    "http_status": "200",
    "http_route": "/payments",
    "log_level": "info"
  }
}
```

> `env` é mantido por compatibilidade; `environment` é o nome canônico do contrato
> (`CONTRACT.md` §4.1 do `immutablelog_siem`). `client_ip`, `error_type` e `error_message`
> só aparecem quando o valor existe (IP resolvido, exceção capturada).

> O campo `payload` é uma **string JSON serializada** — não um objeto.

---

## Comportamento

| Situação                           | Resultado                                               |
|------------------------------------|---------------------------------------------------------|
| `apiKey` vazio                     | O filtro não confere a chave: o evento é enviado mesmo assim, com `Authorization: Bearer ` vazio |
| Path em `SKIP_PATHS`               | Evento ignorado, requisição prossegue normalmente       |
| Status 2xx                         | `type: success`                                         |
| Status 3xx                         | `type: info`                                            |
| Status 4xx / 5xx                   | `type: error`                                           |
| Exceção não tratada que chega ao filtro | `status`/`http_status` = `500` e `type: error` — o status que o container responde, mesmo que a aplicação tenha chamado `setStatus()`/`sendError()` antes do `throw`. Só uma resposta já enviada ao cliente com status < 400 mantém esse status. Vão também `meta.error_type` e `meta.error_message` (até 500 caracteres) e `error` (`type`, `message`, `retryable`) no payload; a exceção é relançada |
| Exceção tratada por `@ExceptionHandler`/`@ControllerAdvice`, `@ResponseStatus` ou `ResponseStatusException` | Não chega ao filtro: o evento sai com o status real da resposta (ex.: 422, 404, 409) e **sem** `error_type`/`error_message`/`error` |
| Rota inexistente                   | `404`, `type: error`; `http_route` sai como `/**` (o padrão do handler de recursos estáticos do Spring Boot), não o caminho |
| Requisição com body que a aplicação não lê | Evento normal, sem `request_body_hash` (o wrapper só guarda o que foi lido) |
| Falha no `sendAsync`               | Exceção silenciada — nunca quebra a aplicação           |

> **Cópias antigas do filtro** (anteriores a esta correção) continuam registrando exceção não tratada como `status: 200` / `type: success` junto de `error_type` até o arquivo ser copiado de novo.

---

## Limitações conhecidas

- **`client_ip` vem de header, sem validação.** O filtro usa o primeiro item de `X-Forwarded-For`; sem ele, `X-Real-IP`; sem os dois, `request.getRemoteAddr()`. Nenhum dos headers é validado, então quem chama a aplicação diretamente (sem um proxy que sobrescreva esses headers) pode forjar o IP registrado.
- **`Idempotency-Key` é um UUID aleatório gerado pelo filtro a cada requisição**, o mesmo valor de `meta.request_id`. Não vem de nenhum header da requisição de entrada.
- **Entrega sem garantia.** Um único `sendAsync()` por evento: sem timeout configurado, sem retry, sem fila. A resposta do ImmutableLog é descartada e qualquer erro (rede, status 4xx/5xx, falha ao montar o evento) é ignorado em silêncio — o evento simplesmente se perde.
- **Sem suporte a trilha (`immutable_trail`):** o filtro não envia nenhum campo de trilha.
- **Chaves reservadas enviadas em `meta`:** `service`, `environment` (e `env`), `http_method`, `http_status`, `http_route` e `log_level` sempre; `client_ip` quando resolvido; `error_type` e `error_message` só quando uma exceção chega ao filtro. Todos são strings.
- **`status`** no payload é o código HTTP numérico lido da resposta no fim do filtro (ou `500` quando uma exceção não tratada atravessa o filtro, ver [Comportamento](#comportamento)); `meta.http_status` é o mesmo valor como string.
- **`error_type` é a exceção que chega ao filtro, não a causa original.** No Spring MVC 6 o `DispatcherServlet` embrulha a exceção do controller: `error_type` sai `jakarta.servlet.ServletException` e `error_message` sai `Request processing failed: java.lang.RuntimeException: boom`. Pelo mesmo motivo `error.retryable` sai `true` mesmo para `IllegalArgumentException`.
- **Endpoints assíncronos do Spring MVC perdem o body da resposta com este filtro** — visto com `Callable` e `DeferredResult`; `WebAsyncTask`, `SseEmitter` e `StreamingResponseBody` usam o mesmo despacho assíncrono e não foram testados. Vale para todas as versões do filtro. O filtro roda só no primeiro dispatch, copia o body (ainda vazio) quando o controller devolve o `Callable` e não está presente quando o resultado é escrito: o cliente recebe `200` com body vazio, e o evento sai com `200` antes de o resultado existir. Não use o filtro em aplicação com endpoints assíncronos.
- **O caminho concreto vai no evento.** `path` no payload e o `event_name` padrão usam `request.getRequestURI()` (sem query string), com os IDs que estiverem na URL. O evento selado não pode ser apagado depois.
- **O hash do body é SHA-256 sem sal.** Não expõe o conteúdo, mas um body previsível pode ser confirmado comparando o hash.
