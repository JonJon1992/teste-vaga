# Serviço de ativação de produtos

Serviço que registra solicitações de ativação de produtos de clientes, permite consultar o status e realiza a
ativação em um provedor externo de forma assíncrona e resiliente.

**Stack:** Java 21 · Spring Boot 3.5 (Spring MVC, Spring Data JPA/Hibernate, Spring Security Resource Server,
Actuator) · PostgreSQL 17 · Flyway · Maven · JUnit 5, Mockito, AssertJ, Testcontainers, WireMock, Awaitility,
ArchUnit · Docker.

## Sumário

- [Como executar](#como-executar)
- [API](#api)
- [Arquitetura](#arquitetura)
- [Decisões técnicas](#decisões-técnicas)
- [Estratégia de testes](#estratégia-de-testes)
- [Cenário de análise](#cenário-de-análise)
- [Produção](#produção)
- [Microsserviços e cloud](#microsserviços-e-cloud)
- [Pontos pendentes](#pontos-pendentes)
- [Melhorias futuras](#melhorias-futuras)
- [Uso de IA](#uso-de-ia)

---

## Como executar

Pré-requisitos: **JDK 21+** e **Docker** (usado pelo Testcontainers e pelo compose). Maven não é necessário: o
projeto usa o Maven Wrapper.

### Build e testes

```bash
./mvnw verify        # Windows: mvnw.cmd verify
```

Compila, roda os testes unitários, os de integração (sobem PostgreSQL e WireMock reais) e as regras de arquitetura.

### Ambiente completo com Docker Compose

```bash
docker compose up --build
```

| Serviço | Porta | Papel |
|---|---|---|
| `app` | 8080 (API), 8081 (health e métricas) | este serviço |
| `postgres` | 5432 | banco de dados |
| `provider-mock` | 8089 | provedor externo simulado (WireMock) |
| `auth` | 8090 | servidor OAuth 2.0 simulado ([mock-oauth2-server](https://github.com/navikt/mock-oauth2-server)) |

Obter um token (Client Credentials) e usar a API:

```bash
TOKEN=$(curl -s -X POST http://localhost:8090/default/token \
  -d grant_type=client_credentials -d client_id=source-system-a -d client_secret=secret \
  | sed -n 's/.*"access_token" *: *"\([^"]*\)".*/\1/p')

curl -i -X POST http://localhost:8080/api/v1/requests \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'

curl -s http://localhost:8080/api/v1/requests/<id> -H "Authorization: Bearer $TOKEN"
```

Clients disponíveis no servidor simulado:

| `client_id` | Escopos |
|---|---|
| `source-system-a` | `activation:read activation:write` |
| `source-system-b` | `activation:read activation:write` (para testar o isolamento entre consumidores) |
| `reporting-system` | `activation:read` (para testar o `403`) |

O provedor simulado responde `200` por padrão e simula falhas pelo `productCode`:

| `productCode` | Resposta do provedor | Status final esperado |
|---|---|---|
| qualquer outro | `200` | `COMPLETED` |
| `INVALID-PRODUCT` | `400` | `FAILED` sem retry |
| `BROKEN-PRODUCT` | `500` | `FAILED` após 5 tentativas com backoff |
| `THROTTLED-PRODUCT` | `429` + `Retry-After: 10` | `FAILED` após 5 tentativas espaçadas pelo `Retry-After` |
| `SLOW-PRODUCT` | `200` após 8 s (timeout de leitura é 5 s) | `FAILED` após 5 timeouts, sempre com a mesma `Idempotency-Key` |

Observabilidade local (porta de gerenciamento): `http://localhost:8081/actuator/health/readiness` e
`http://localhost:8081/actuator/prometheus`.

### Rodando só a aplicação

`./mvnw spring-boot:run` usa um PostgreSQL em `localhost:5432` e o servidor OAuth em `localhost:8090` (por exemplo,
`docker compose up postgres auth provider-mock`). Toda a configuração vem de variáveis de ambiente:

| Variável | Padrão | Descrição |
|---|---|---|
| `SERVER_PORT`, `MANAGEMENT_PORT` | `8080`, `8081` | porta da API e porta interna de health e métricas |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/activation`, `activation`, `activation` | banco |
| `JWT_ISSUER_URI` | `http://localhost:8090/default` | emissor esperado no `iss` |
| `JWT_JWK_SET_URI` | `http://localhost:8090/default/jwks` | chaves públicas para validar a assinatura |
| `JWT_AUDIENCE` | `product-activation-service` | audiência esperada no `aud` |
| `JWT_PRINCIPAL_CLAIM` | `sub` | claim que identifica o sistema consumidor |
| `PROVIDER_BASE_URL` | `http://localhost:8089` | URL do provedor |
| `PROVIDER_API_KEY` | vazio | credencial enviada ao provedor como Bearer |
| `PROVIDER_CONNECT_TIMEOUT`, `PROVIDER_READ_TIMEOUT` | `2s`, `5s` | timeouts do cliente HTTP |
| `PROCESSING_ENABLED` | `true` | liga o worker nesta instância |
| `PROCESSING_POLL_INTERVAL`, `PROCESSING_RECOVERY_INTERVAL` | `1s`, `30s` | frequência do worker e da reconciliação |
| `PROCESSING_BATCH_SIZE`, `PROCESSING_LEASE` | `10`, `30s` | tamanho do lote e prazo da reserva (o lease precisa ser maior que a soma dos timeouts do provedor, senão a aplicação não sobe) |
| `PROCESSING_MAX_ATTEMPTS` | `5` | tentativas antes de `FAILED` |
| `PROCESSING_INITIAL_BACKOFF`, `PROCESSING_MAX_BACKOFF` | `2s`, `5m` | backoff exponencial |
| `API_MAX_REQUEST_BODY_SIZE` | `16KB` | tamanho máximo do corpo da requisição |
| `API_RATE_LIMIT_PER_MINUTE` | `120` | requisições por minuto por consumidor, em cada instância |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | vazio (texto) | `ecs` para logs JSON (usado no compose) |

---

## API

| Método | Rota | Escopo | Respostas |
|---|---|---|---|
| `POST` | `/api/v1/requests` | `activation:write` | `201` + `Location`, `200` (retry), `400`, `401`, `403`, `409`, `413`, `429` |
| `GET` | `/api/v1/requests/{id}` | `activation:read` | `200`, `400` (id inválido), `401`, `403`, `404`, `429` |
| `GET` | `/api/v1/requests?status=&customerId=&page=&size=` | `activation:read` | `200`, `400`, `401`, `403`, `429` |

```json
// POST /api/v1/requests
{ "externalId": "EXT-12345", "customerId": "CUST-10001", "productCode": "INTERNET-500" }

// 201 Created — Location: /api/v1/requests/01a17885-b590-7c4e-9a51-2f8d7b1e6c90
{
  "id": "01a17885-b590-7c4e-9a51-2f8d7b1e6c90",
  "externalId": "EXT-12345",
  "customerId": "CUST-10001",
  "productCode": "INTERNET-500",
  "status": "PENDING",
  "createdAt": "2026-10-26T14:03:11.120Z",
  "updatedAt": "2026-10-26T14:03:11.120Z",
  "attempts": 0
}
```

- **Validação:** `externalId`, `customerId` e `productCode` são obrigatórios, têm no máximo 64 caracteres e aceitam
  apenas letras, números, `.`, `_` e `-`.
- **Datas:** UTC, ISO-8601.
- **`failureReason`:** aparece quando houve falha (motivo genérico; detalhes técnicos ficam só no log).
- **Listagem:** sempre restrita ao consumidor autenticado; `page` começa em 0, `size` padrão 20 e máximo 100,
  ordenação da mais recente para a mais antiga.
- **Correlação:** o header `X-Correlation-Id` é aceito (8 a 64 caracteres alfanuméricos ou `-`) ou gerado, devolvido
  na resposta, gravado com a solicitação e enviado ao provedor.
- **Erros:** RFC 9457 (`application/problem+json`), com `traceId` igual ao `X-Correlation-Id`:

```json
{
  "type": "https://api.exemplo.com/problems/validation-error",
  "title": "Dados inválidos",
  "status": 400,
  "detail": "Um ou mais campos não passaram na validação.",
  "instance": "/api/v1/requests",
  "errors": [{ "field": "customerId", "message": "é obrigatório" }],
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736"
}
```

| Situação | Status | `type` |
|---|---|---|
| Campos inválidos, parâmetro com tipo errado | 400 | `validation-error` |
| JSON malformado ou ausente | 400 | `malformed-request` |
| Sem token, token inválido ou expirado | 401 | `unauthorized` |
| Escopo insuficiente | 403 | `forbidden` |
| Solicitação inexistente (ou de outro consumidor) | 404 | `not-found` |
| `externalId` reutilizado com dados diferentes | 409 | `external-id-conflict` |
| Corpo acima de 16 KB | 413 | `payload-too-large` |
| Consumidor acima do limite de requisições (com header `Retry-After`) | 429 | `too-many-requests` |
| Banco indisponível | 503 | `service-unavailable` |
| Erro inesperado (sem mensagem nem stack trace) | 500 | `internal-error` |

---

## Arquitetura

```
api ──────────────┐
  controllers,    │
  DTOs, erros,    ├──► application ──► domain ◄── infrastructure
  segurança HTTP  │     casos de uso     agregado,     persistência JPA,
                  │     e transações     regras,       cliente HTTP,
                  └───────────────────►  portas        agendamento, métricas
```

```
src/main/java/dev/challenge/activation
├── domain
│   ├── activation   ActivationRequest (agregado), ActivationStatus, RetryPolicy, ActivationOutcome (sealed),
│   │                portas ActivationRequestRepository e ActivationProvider
│   └── shared       PageQuery, ResultPage, Guard, UuidV7
├── application      CreateActivationRequestUseCase, ActivationRequestQueries, ActivationDispatcher
├── api              ActivationRequestController, DTOs (records), ApiExceptionHandler, SecurityConfiguration
└── infrastructure
    ├── persistence  adapter JPA, consultas SKIP LOCKED, métricas de backlog
    ├── provider     HttpActivationProvider (RestClient), Retry-After
    ├── scheduling   agendador do worker e configuração de retentativa
    └── config       Clock
```

As regras de dependência são verificadas por ArchUnit (`ArchitectureTest`). Detalhes em
[ADR 0001](docs/adr/0001-camadas-e-ddd-pragmatico.md).

---

## Decisões técnicas

Registros de decisão completos em [`docs/adr`](docs/adr).

### Modelo de domínio e estados

```
PENDING ──► PROCESSING ──► COMPLETED
   ▲            │
   └────────────┤ (falha temporária, com tentativas restantes)
                └────────► FAILED   (rejeição do provedor ou limite de tentativas)
```

- O status só muda por métodos do agregado (`startProcessing`, `applyOutcome`, `releaseExpiredLease`); não há
  setters. Transições inválidas (ex.: `COMPLETED → PROCESSING`) lançam `InvalidStatusTransitionException`.
- **Falha temporária volta para `PENDING`** com `attempts` incrementado e `next_attempt_at` agendado. Ficar em
  `PROCESSING` misturaria "chamando agora" com "vou chamar depois" e esconderia solicitações presas.
- **Escrita concorrente:** `@Version` (lock otimista). Se um worker demora além do lease e outro reprocessa a
  solicitação, a escrita atrasada falha e é descartada.

### Duplicidade (item 3)

**A garantia é a constraint única `(client_id, external_id)` no PostgreSQL.** Verificar e depois inserir não basta:
duas requisições simultâneas podem ler "não existe" antes de qualquer uma inserir, e isso vale também entre
instâncias diferentes, onde nenhum lock em memória ajuda.

O fluxo de criação busca pelo `externalId` (atalho para o retry comum) e, se não encontra, insere. Se o insert viola a
constraint, a requisição perdeu a corrida: o registro vencedor é relido em uma **nova transação** (no PostgreSQL a
transação do insert falho fica abortada; por isso a criação não é um único `@Transactional`).

| Cenário | Decisão |
|---|---|
| Mesmo `externalId`, mesmo conteúdo (retry legítimo) | `200 OK` com o registro existente. O retry é idempotente: o cliente recebe o mesmo resultado sem tratar como erro algo que deu certo. |
| Mesmo `externalId`, conteúdo diferente | `409 Conflict` — "O externalId X já foi utilizado em uma solicitação com dados diferentes". Indica bug ou reuso indevido no sistema de origem; aceitar ou sobrescrever esconderia o problema. |
| Duas requisições idênticas em paralelo | Uma recebe `201`; a outra viola a constraint, relê e recebe `200` com o mesmo `id`. |

A unicidade é **por consumidor** e não global: o `externalId` vem do sistema de origem, e dois sistemas podem usar o
mesmo valor. Com unicidade global, um sistema receberia `409` (ou o registro de outro) por um id que nunca usou.
[ADR 0002](docs/adr/0002-unicidade-de-externalid.md).

### Integração com o provedor (item 5)

**Assíncrono.** O `POST` grava a solicitação em `PENDING` e responde em milissegundos; um worker faz a chamada. Se o
provedor ficar lento (400 ms → 3 s), a API não sente; só o tempo até `COMPLETED` aumenta.

**Transação x chamada remota.** A chamada HTTP nunca acontece com transação aberta:

1. Transação curta: `SELECT ... FOR UPDATE SKIP LOCKED` de um lote de `PENDING` vencidas → `PROCESSING`, com lease
   (prazo da reserva) de 30 s.
2. Chamada ao provedor sem transação (lote em paralelo com virtual threads).
3. Nova transação: aplica o resultado (protegida por `@Version`).

**Garantia de envio mesmo com queda.** A própria tabela é a outbox: a solicitação gravada fica em `PENDING` até ser
processada. Se a aplicação cair entre o commit e a chamada, outra instância (ou a mesma, ao voltar) a encontra. Se
cair **durante** a chamada, a solicitação fica em `PROCESSING` com lease vencido; a rotina de reconciliação (a cada
30 s) a devolve para `PENDING`.

**Timeout é ambíguo.** O provedor pode ter ativado o produto. Toda chamada envia `Idempotency-Key` = id da
solicitação, igual em todas as tentativas, para o provedor reconhecer a repetição e não ativar duas vezes. O teste de
integração verifica isso: timeout seguido de sucesso, com as duas chamadas usando a mesma chave.

**Timeouts explícitos:** conexão 2 s e leitura 5 s, configuráveis. O lease de 30 s precisa ser maior que a soma dos
dois; se não for, a aplicação recusa subir, porque uma chamada lenta seria reprocessada enquanto ainda está em
andamento. O cliente usa HTTP/1.1 explicitamente (a negociação
h2c do `HttpClient` do JDK causava `RST_STREAM` em respostas sem corpo).

**Limite de tentativas e backoff:** exponencial a partir de 2 s, com jitter (entre metade e o valor cheio), teto de
5 min, máximo de 5 tentativas. Ao atingir o limite, a solicitação vai para `FAILED` com o último erro no motivo.

| Retorno do provedor | Novo status | Nova tentativa? | Justificativa |
|---|---|---|---|
| Sucesso (2xx) | `COMPLETED` | Não | Ativação confirmada. |
| HTTP 400 (e demais 4xx, exceto 408/429) | `FAILED` | Não | Problema nos dados enviados; repetir não muda o resultado e só gera carga. |
| HTTP 500 (e demais 5xx, 408) | `PENDING` | Sim, com backoff | Falha do lado do provedor, possivelmente momentânea. |
| Timeout / falha de conexão | `PENDING` | Sim, com backoff e a mesma `Idempotency-Key` | Resultado desconhecido; a chave torna o retry seguro. |
| HTTP 429 / 503 com `Retry-After` | `PENDING` | Sim, no maior entre o backoff e o `Retry-After` | O provedor pediu para esperar; insistir antes pioraria a sobrecarga. |
| Limite de tentativas atingido | `FAILED` | Não | Evita retry infinito; fica visível para reprocessamento manual. |

**Premissas sobre o provedor** (contrato não definido no enunciado): aceita `POST /provider/v1/activate` com
`{requestId, customerId, productCode}`, honra `Idempotency-Key` devolvendo o resultado original em repetições e usa
4xx apenas para erros definitivos.

**Circuit breaker:** não implementado. Como a chamada é assíncrona, um provedor fora do ar não derruba a API, e o
backoff com jitter já reduz a pressão sobre ele. Com volume alto, valeria um circuit breaker (Resilience4j) em volta
do `HttpActivationProvider`: com o circuito aberto, o worker devolveria o lote para `PENDING` sem chamar o provedor.
[ADR 0003](docs/adr/0003-processamento-assincrono-com-lease.md).

### Erros (item 6)

Todos os erros seguem Problem Details com `traceId` (tabela na seção [API](#api)). Nenhuma resposta expõe stack trace,
mensagem de exceção interna, URL do provedor ou corpo da resposta dele; esses detalhes vão apenas para o log,
correlacionados pelo `traceId`. O teste `returns500WithoutLeakingInternalDetails` garante isso.

### Segurança (item 7) — implementada

- **OAuth 2.0 Client Credentials:** comunicação entre sistemas, sem usuário final.
- **Resource Server JWT:** valida assinatura (JWKS do emissor, com rotação de chaves pelo `kid`), `iss`, `aud`, `exp`
  e `nbf`.
- **Escopos:** `activation:write` cria; `activation:read` consulta e lista. Qualquer outra rota é negada.
- **Isolamento entre consumidores (BOLA):** a claim `sub` é gravada como dono da solicitação. Consultas e listagens
  filtram por ela, e um id de outro consumidor responde `404` para não confirmar que existe.
- **Credencial do provedor:** `PROVIDER_API_KEY` vem de um gerenciador de segredos (Secrets Manager, Key Vault,
  Vault) injetado no container. A rotação sem indisponibilidade usa duas chaves válidas em sobreposição.
- **Testes:** `401` sem token e com token inválido, `403` por escopo, isolamento entre consumidores e mapeamento da
  claim `scope`, tudo sem servidor de autenticação real.
- **Limite de tamanho do corpo:** o `RequestBodyLimitFilter` roda antes da autenticação e responde `413`.
  - Com `Content-Length` acima do limite, a requisição é recusada sem ler o corpo.
  - Sem `Content-Length` (chunked), a leitura é interrompida assim que o limite é ultrapassado. Assim um cliente não
    consegue mandar um corpo enorme só por omitir o header.
- **Limite de taxa por consumidor:** o `ClientRateLimitInterceptor` usa um token bucket (Bucket4j) por `sub` do token
  e responde `429` com `Retry-After`.
  - Roda depois da autenticação, então o limite é por sistema e não por IP.
  - Um consumidor ruidoso não afeta os outros.
  - O contador é **por instância**: com N réplicas, o limite efetivo é N × o configurado.
  - Para um limite global exato, o mesmo Bucket4j usa um backend compartilhado (Redis, via `bucket4j-redis`), ou o
    controle sobe para o API gateway.

  Na borda, o gateway continua sendo a primeira barreira: protege também contra tráfego não autenticado.

[ADR 0004](docs/adr/0004-seguranca-resource-server-jwt.md).

### Índices

| Índice | Uso | Justificativa |
|---|---|---|
| `pk (id)` — UUID v7 | `GET /{id}` e atualizações do worker | ids ordenados pelo tempo: cada insert vai para o fim do índice, sem fragmentar a árvore (ver abaixo) |
| `uk (client_id, external_id)` | unicidade e busca do retry | garante a regra e serve a consulta mais frequente do `POST` |
| `ix_due (status, next_attempt_at) WHERE status IN ('PENDING','PROCESSING')` | claim do worker, reconciliação e métrica de backlog | **parcial**: só cobre solicitações não finalizadas, então fica pequeno mesmo com milhões de `COMPLETED` |
| `ix (client_id, status, created_at DESC)` | listagem filtrada por status | filtra e já entrega na ordem da paginação, sem sort |
| `ix (client_id, customer_id, created_at DESC)` | listagem filtrada por cliente | idem; com os dois filtros o planner usa um e filtra o outro |

`client_id` é sempre o primeiro campo porque toda consulta da API é restrita ao consumidor.

**Ids com UUID v7 (RFC 9562).** Os primeiros 48 bits são o instante de criação em milissegundos; o restante é
aleatório (74 bits, `SecureRandom`). Com UUID v4, totalmente aleatório, cada insert cai em uma página qualquer do
índice da chave primária: mais page splits, índice fragmentado e páginas "frias" sendo lidas do disco. Com v7, os
inserts são praticamente sequenciais, como em um `BIGSERIAL`, sem perder o que o UUID oferece: id gerado sem ida ao
banco, que não revela volume nem permite adivinhar ids vizinhos.

- O id é gerado **no domínio** (`UuidV7.generate(now)`, em `domain.shared`), com o mesmo instante do `createdAt`. A
  identidade existe antes de persistir (necessária para a `Idempotency-Key`) e os testes continuam determinísticos com
  `Clock` fixo.
- A implementação é própria, com poucas linhas e testada (versão, variante, timestamp, ordenação e unicidade no mesmo
  milissegundo), em vez de uma biblioteca só para isso.
- O id expõe o instante de criação, o que não é problema aqui porque o `createdAt` já é público na API.
- A coluna continua `UUID` no PostgreSQL, sem migração.

### Observabilidade

- **Porta de gerenciamento separada (8081):** health e métricas não passam pela porta da API (8080), que responde
  `404` para `/actuator/**`. Em produção a 8081 não é exposta no ingress, só na rede interna.
- **Health:** `/actuator/health/liveness` e `/actuator/health/readiness` (readiness inclui o banco), públicos na
  porta de gerenciamento para o orquestrador.
- **Métricas Prometheus** (`/actuator/prometheus`):
  - `http_server_requests`: latência da API por rota/status, com histograma para p95/p99;
  - `http_client_requests`: latência e taxa de erro do provedor, inclusive timeouts;
  - `activation_provider_outcomes_total{outcome}`: resultados (`activated`, `rejected`, `temporary_failure`);
  - `activation_requests{status}`: backlog em `PENDING` e `PROCESSING`.
- **Logs:** `traceId` no MDC desde a requisição; o worker restaura o `traceId` gravado com a solicitação antes de
  chamar o provedor. Em JSON (ECS) com `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`. Tokens, credenciais e corpos de
  requisição não são logados.
- **Desligamento gracioso:** até 30 s para requisições em andamento e para a rodada do worker.

---

## Estratégia de testes

| Nível | Classes | O que garante | Por quê nesse nível |
|---|---|---|---|
| Domínio (unitário, sem Spring) | `ActivationRequestTest`, `ActivationStatusTest`, `RetryPolicyTest`, `UuidV7Test` | transições válidas e inválidas, limite de tentativas, backoff e jitter, formato e ordenação dos ids | regras de negócio puras: rápidas e determinísticas (`Clock` e aleatoriedade injetados) |
| Aplicação (unitário, Mockito) | `CreateActivationRequestUseCaseTest`, `ActivationRequestQueriesTest`, `ActivationDispatcherTest` | idempotência da criação, corrida simulada, isolamento, orquestração do worker | lógica de coordenação sem depender de banco ou HTTP |
| Web (`@WebMvcTest`) | `ActivationRequestControllerTest`, `ApiSecurityTest`, `RequestLimitsTest` | contrato HTTP, validação, Problem Details, 401/403, 413 e 429 | serialização, status e segurança exigem o MVC real, mas não o banco |
| Persistência (Testcontainers) | `JpaActivationRequestRepositoryTest` | constraint única, `@Version`, filtros, `SKIP LOCKED` entre transações | comportamento de banco não se simula com H2 ou mock |
| Cliente HTTP (WireMock) | `HttpActivationProviderTest`, `RetryAfterHeaderTest` | mapeamento de cada retorno, headers enviados, timeout real | timeouts e falhas de conexão precisam de um servidor de verdade |
| Integração ponta a ponta | `ActivationFlowIntegrationTest`, `ActivationApplicationTests` | 10 POSTs simultâneos → 1 registro; fluxo até `COMPLETED`/`FAILED`; retry após 503 e timeout; probes e métricas | o que só aparece com tudo junto: transações reais, worker agendado, concorrência |
| Arquitetura (ArchUnit) | `ArchitectureTest` | direção das dependências, domínio sem frameworks | evita que a estrutura se degrade com o tempo |

---

## Cenário de análise

> API 300 ms → 2,5 s · CPU 45% · memória 60% · algumas consultas 50 ms → 1,2 s · provedor 400 ms → 3 s

**O que investigaria primeiro.** CPU e memória normais indicam que a aplicação não está computando mais, e sim
**esperando** (I/O, locks ou fila por conexão). Começaria quebrando os 2,5 s por endpoint (`http_server_requests` por
`uri`) e olhando o pool de conexões (`hikaricp_connections_pending`, `hikaricp_connections_acquire`): se as
requisições esperam conexão, o tempo de "consulta" medido pela aplicação inclui essa espera.

**Aplicação, banco ou integração?**
- **Integração:** `http_client_requests` mostra a latência do provedor isolada. Neste serviço o `POST` não chama o
  provedor, então um provedor lento sozinho não deveria afetar a API. Se afeta, algo está acoplando os dois.
- **Banco:** comparar o tempo da query no PostgreSQL (`pg_stat_statements`, `log_min_duration_statement`) com o tempo
  visto pela aplicação. Se o banco executa rápido e a aplicação vê lento, o problema é espera por conexão ou lock.
  `pg_stat_activity` (sessões `idle in transaction`, `wait_event`) e `pg_locks` mostram contenção.
- **Aplicação:** se banco e provedor estão normais, olhar threads ocupadas do Tomcat, pausas de GC
  (`jvm_gc_pause_seconds`) e um thread dump.

**Logs e métricas úteis:** p95/p99 por endpoint, métricas do Hikari, `http_client_requests` com timeouts,
`activation_requests{status="PENDING"}` (backlog crescendo?), `activation_provider_outcomes_total` (proporção de
`temporary_failure`), `pg_stat_statements`, plano das queries lentas (`EXPLAIN ANALYZE`) e logs filtrados por
`traceId` das requisições lentas. Com tracing distribuído, um trace mostraria direto onde o tempo é gasto.

**Melhorias possíveis:** índice ou reescrita das consultas lentas a partir do `EXPLAIN`; dimensionar o pool
(`conexões × instâncias ≤ max_connections`); isolar o worker da API (pool separado ou instâncias dedicadas,
`PROCESSING_ENABLED=false` nas instâncias da API); limitar a concorrência contra o provedor e abrir circuit breaker
quando ele degradar; cache para leituras repetidas; arquivar solicitações finalizadas antigas.

**Causa comum — hipótese.** Os três sintomas podem vir de **conexões presas pela lentidão do provedor**. Se algum
caminho chama o provedor com transação aberta (por exemplo, alguém anotou o worker com `@Transactional`), cada chamada
segura uma conexão e os locks das linhas reservadas pelo tempo da chamada (agora 3 s). O pool esgota, a API espera por
conexão (2,5 s), as queries parecem lentas porque esperam conexão ou lock (1,2 s) e a CPU fica baixa porque as threads
estão bloqueadas. Uma variação: retries agressivos sem backoff contra um provedor degradado criam uma tempestade de
chamadas e um backlog que pressiona o banco.

Como confirmar: `hikaricp_connections_pending > 0` e tempo de aquisição alto no mesmo horário em que
`http_client_requests` subiu; `pg_stat_activity` com muitas sessões `idle in transaction` durando cerca de 3 s; nos
traces, o span de banco começando só depois do span HTTP. Para a variação de retry: taxa de chamadas ao provedor muito
acima da taxa de criação, e backlog `PENDING` crescendo. A arquitetura deste serviço evita as duas causas
(chamada fora de transação, backoff com jitter), e manter isso é parte da correção.

**Validar a correção depois de publicada:** publicar com canary e comparar contra a versão anterior as mesmas
métricas que mostraram o problema (p95/p99 por endpoint, conexões pendentes, tempo das queries, backlog), com
anotação do deploy no dashboard. Definir antes o critério de sucesso (ex.: p95 < 400 ms por 24 h, zero conexões
pendentes) e manter um alerta nessa métrica para detectar regressão. Se possível, reproduzir antes em staging com
teste de carga e provedor simulado com 3 s de latência.

---

## Produção

- **Imagem:** o `Dockerfile` multi-stage compila com JDK e roda com JRE 21 Alpine, em camadas (dependências separadas do
  código), usuário não-root, `MaxRAMPercentage=75` e encerramento em `OutOfMemoryError` para o orquestrador reiniciar.
  A imagem é gerada no CI e publicada em um registry com a tag do commit.
- **Execução:** em Kubernetes (ou ECS/Cloud Run), com pelo menos 2 réplicas:
  - probes de liveness e readiness nos endpoints do Actuator, na porta 8081;
  - `requests`/`limits` de CPU e memória;
  - `terminationGracePeriodSeconds` maior que os 30 s do desligamento gracioso.
- **Configuração e segredos:** configuração por variáveis de ambiente (ConfigMap). Senha do banco e
  `PROVIDER_API_KEY` vêm do gerenciador de segredos do provedor cloud, nunca da imagem ou do repositório.
- **Banco:** PostgreSQL gerenciado (RDS, Cloud SQL), com backup e réplica. O Flyway roda no startup e usa lock
  próprio, então várias réplicas subindo juntas não conflitam. Em ambientes mais restritos, rodaria como job
  separado antes do deploy.
- **Borda:** API gateway ou ingress com TLS, limite de tamanho do corpo e rate limit por `client_id`.
- **Observabilidade:**
  - Prometheus coleta `/actuator/prometheus` na porta de gerenciamento (8081), que não é exposta no ingress;
  - logs em JSON (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`) vão para a plataforma central;
  - alertas em p95 da API, taxa de erro do provedor e backlog `PENDING`.
- **Escala:** HPA por CPU ou latência. O pool de conexões é dimensionado considerando o número máximo de réplicas.

---

## Microsserviços e cloud

- **Várias instâncias ao mesmo tempo.** Funciona sem coordenação externa:
  - a constraint única vale para todas as instâncias;
  - `SKIP LOCKED` divide o trabalho do worker entre elas;
  - o lease recupera solicitações de uma instância que caiu;
  - `@Version` impede que uma escrita atrasada sobrescreva outra.

  Para escalar API e worker de forma independente, o mesmo artefato roda em dois deployments, com
  `PROCESSING_ENABLED=false` nas instâncias da API.
- **Configurações e segredos:** variáveis de ambiente para configuração; segredos no gerenciador do provedor cloud,
  injetados via CSI driver ou integração nativa; rotação sem redeploy quando possível.
- **Avisar outros microsserviços.** Na mesma transação que marca `COMPLETED` ou `FAILED`, gravar um evento em uma
  tabela `outbox`. Um publicador (ou CDC com Debezium) envia o evento `ActivationCompleted`/`ActivationFailed` para um
  tópico (Kafka, SNS ou Pub/Sub), com o id da solicitação como chave. Os consumidores tratam o evento de forma
  idempotente, porque a entrega é *at-least-once*. Publicar direto após o commit perderia eventos numa queda.
- **CI/CD.**
  1. O pull request roda `./mvnw verify` (unitários, Testcontainers, ArchUnit) e o build da imagem; isso já está
     implementado em `.github/workflows/ci.yml`.
  2. No merge para `main`: varredura da imagem (Trivy), push para o registry com a tag do commit, deploy em staging
     via Helm/Argo CD e smoke tests.
  3. Promoção para produção com canary e rollback automático se os alertas dispararem.

---

## Pontos pendentes

| Item | Situação | Como faria |
|---|---|---|
| Rate limit distribuído | Implementado por instância | Backend compartilhado no Bucket4j (`bucket4j-redis` com Lettuce) para um limite exato entre réplicas. Com Redis indisponível, deixaria a requisição passar (*fail-open*) e registraria métrica e alerta, para não derrubar a API por um componente auxiliar. |
| Circuit breaker | Não implementado | Resilience4j em volta do `HttpActivationProvider`; com o circuito aberto, devolve a solicitação para `PENDING` sem chamar o provedor. |
| Tracing distribuído | Não implementado | `micrometer-tracing-bridge-otel` + exporter OTLP. O `traceId` do MDC passaria a ser o do W3C `traceparent`, propagado automaticamente pelo `RestClient`. |
| Evento de conclusão | Não implementado | Outbox + publicação em tópico (seção anterior). |
| Reprocessar `FAILED` | Não implementado | Endpoint administrativo (escopo próprio) que devolve a solicitação para `PENDING` zerando as tentativas, com auditoria. |

---

## Melhorias futuras

- **Particionamento ou arquivamento** de solicitações finalizadas antigas, mantendo a tabela quente pequena.
- **Contadores de transições finais** (`COMPLETED`/`FAILED`) emitidos pelo worker, complementando o backlog.
- **Testes de contrato** com o provedor (Pact) e testes de carga (Gatling/k6) no pipeline.
- **Qualidade no CI:** cobertura (JaCoCo) e testes de mutação (PIT).
- **Imagem menor:** runtime com `jlink` e CDS/AOT cache para startup mais rápido.
- **API:** `ETag`/`If-None-Match` no `GET` por id para polling barato; webhook ou evento para evitar polling.

---

## Uso de IA

O desenvolvimento foi feito com o **Claude Code** como par:
- **Leitura do enunciado** e divisão do trabalho em tarefas, uma por commit.
- **Geração do código e dos testes** de cada tarefa, com o build rodando a cada passo.
- **Diagnóstico de problemas encontrados no caminho:** a incompatibilidade de HTTP/2 (h2c) do cliente JDK com o
  WireMock e o bit de execução do `mvnw` no Git do Windows.
- **Redação deste README e dos ADRs.**

As decisões de organização do código e arquitetura foram tomadas e revisadas por mim.
O ambiente com Docker Compose foi validado manualmente, com os cenários de criação, retry, conflito, validação, 401/403, isolamento entre consumidores
e falha no provedor.
