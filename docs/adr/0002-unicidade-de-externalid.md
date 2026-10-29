# ADR 0002 — Unicidade de `externalId` garantida pelo banco, por consumidor

**Status:** aceito

## Contexto

Sistemas de origem fazem retry. Duas requisições iguais podem chegar quase ao mesmo tempo, em instâncias diferentes
do serviço. Verificar no banco se o `externalId` existe e depois inserir não basta: entre a leitura e a escrita há uma
janela em que as duas requisições leem "não existe" e as duas inserem.

## Decisão

1. **Constraint única no PostgreSQL** `uk_activation_request_client_external_id (client_id, external_id)`. É a única
   garantia que vale com várias threads e várias instâncias.
2. A busca prévia por `externalId` é só um atalho para o retry comum (evita uma escrita e uma exceção); não é ela que
   impede a duplicidade.
3. Se o `INSERT` viola a constraint, o adapter traduz para `DuplicateActivationRequestException` e o caso de uso relê o
   registro vencedor **em uma nova transação** (a transação do insert falho já está abortada no PostgreSQL). Por isso o
   caso de uso de criação não é `@Transactional` como um todo.
4. A unicidade é **por consumidor** (`client_id` = claim `sub` do token), não global: o `externalId` é gerado pelo
   sistema de origem, e dois sistemas podem usar o mesmo valor. Com unicidade global, o sistema B receberia 409 (ou,
   pior, o registro do sistema A) por um id que ele nunca usou, o que também vazaria a existência de dados de outro
   consumidor.

Semântica da resposta:

| Cenário | Resposta |
|---|---|
| `externalId` novo | `201 Created` + `Location` |
| Mesmo `externalId`, mesmo conteúdo (retry legítimo) | `200 OK` com o registro original |
| Mesmo `externalId`, conteúdo diferente | `409 Conflict` (Problem Details `external-id-conflict`) |
| Duas requisições idênticas em paralelo | Uma recebe `201`; a outra viola a constraint, relê e recebe `200` com o mesmo registro |

## Consequências

- Retry é idempotente e seguro para o cliente: ele não precisa distinguir "já criei" de "criei agora" para seguir.
- `200` em vez de `409` para o retry legítimo evita que o sistema de origem trate como erro algo que deu certo.
- O teste `ActivationFlowIntegrationTest` dispara 10 requisições simultâneas contra o PostgreSQL real e verifica
  exatamente um registro, uma resposta `201` e nove `200` com o mesmo id.

## Alternativas consideradas

- **`INSERT ... ON CONFLICT DO NOTHING RETURNING`:** uma ida ao banco a menos, mas exige SQL nativo específico de
  PostgreSQL no caminho principal; a abordagem atual funciona com JPA padrão e o custo extra só aparece no caso raro
  da corrida.
- **Lock distribuído (Redis) ou `SELECT ... FOR UPDATE` antes do insert:** mais peças e não protege contra uma linha
  que ainda não existe.
- **Header `Idempotency-Key` do cliente:** útil para APIs genéricas; aqui o `externalId` já cumpre esse papel.
