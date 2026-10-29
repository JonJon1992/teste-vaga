# ADR 0003 — Processamento assíncrono com a tabela como outbox e reserva por lease

**Status:** aceito

## Contexto

Após criar a solicitação, o serviço precisa chamar o provedor externo. O provedor pode ficar lento (o cenário de
análise mostra 400 ms → 3 s), falhar temporariamente ou não responder. Fazer a chamada HTTP dentro do `POST` ou dentro de
uma transação aberta acopla a latência da API à do provedor e segura conexões do banco durante a chamada. Fazer a
chamada depois do commit, na mesma thread, perde a solicitação se a aplicação cair entre o commit e a chamada.

## Decisão

- O `POST /api/v1/requests` apenas grava a solicitação em `PENDING` (com `next_attempt_at = agora`) e responde. A
  própria tabela é a outbox: o que foi gravado será processado, mesmo após uma queda.
- O `ActivationDispatcher`, acionado por `@Scheduled` em todas as instâncias, executa três passos:
  1. **Reserva** (transação curta): `SELECT ... FOR UPDATE SKIP LOCKED` de um lote de `PENDING` vencidas, que passam a
     `PROCESSING` com `attempts + 1` e `next_attempt_at = agora + lease`.
  2. **Chamada** ao provedor fora de qualquer transação, com `Idempotency-Key` = id da solicitação. O lote é
     processado em paralelo com virtual threads, para que o lease precise cobrir uma chamada e não o lote todo.
  3. **Resultado** (nova transação): o agregado aplica o resultado; `@Version` descarta a escrita se outro processo
     já reprocessou a solicitação.
- Uma segunda rotina reconcilia solicitações em `PROCESSING` com lease vencido (queda no meio da chamada) e as devolve
  para `PENDING`, ou `FAILED` se o limite de tentativas acabou.
- **Falha temporária volta para `PENDING`** com contador de tentativas e próxima tentativa agendada por backoff
  exponencial com jitter (respeitando `Retry-After` quando maior). Ficar em `PROCESSING` com contador misturaria
  "estou chamando agora" com "vou chamar depois" e esconderia solicitações presas.

## Consequências

- A latência da API não depende do provedor; o cliente acompanha o status por `GET`.
- Várias instâncias processam em paralelo sem coordenação externa: `SKIP LOCKED` divide o trabalho, o lease recupera
  falhas e `@Version` protege a escrita final.
- A entrega ao provedor é *at-least-once*: após timeout ou queda a mesma solicitação pode ser enviada de novo. A
  `Idempotency-Key` é o que evita ativação duplicada, e depende de o provedor suportá-la (premissa documentada).
- O lease (30 s por padrão) precisa ser maior que timeout de conexão + leitura (2 s + 5 s). A configuração é
  validada no startup: com um lease menor, a aplicação não sobe.
- Polling a cada 1 s adiciona até ~1 s de atraso e uma consulta leve por instância (índice parcial).

## Alternativas consideradas

- **Síncrono no `POST`:** simples, mas a API herda a lentidão e as falhas do provedor.
- **`@Async` / evento após commit:** perde solicitações em queda e não oferece retry agendado.
- **Fila (Kafka/SQS/RabbitMQ):** melhor para alto volume, mas exige outbox de qualquer forma para não perder a
  mensagem entre o commit e a publicação; fica como evolução (ver README).
- **Circuit breaker (Resilience4j):** não implementado. Como a chamada é assíncrona, uma indisponibilidade do
  provedor não derruba a API; o backoff com jitter e o limite de tentativas já reduzem a pressão. Com alto volume,
  um circuit breaker em volta do `HttpActivationProvider` evitaria gastar o lote em chamadas fadadas a falhar.
