# ADR 0001 — Camadas e DDD pragmático

**Status:** aceito

## Contexto

O serviço tem um único agregado (`ActivationRequest`) com regras de transição de status, uma integração externa e
uma API REST. O enunciado pede boas práticas, mas valoriza soluções simples em vez de muitas camadas sem necessidade.

## Decisão

Quatro pacotes, com a direção de dependência verificada por ArchUnit (`ArchitectureTest`):

| Pacote | Responsabilidade | Pode depender de |
|---|---|---|
| `domain` | Agregado, regras de transição, política de retentativa e portas (`ActivationRequestRepository`, `ActivationProvider`) | nada além do JDK e anotações `jakarta.persistence` |
| `application` | Casos de uso e fronteiras de transação | `domain` |
| `api` | Controllers, DTOs, Problem Details, segurança HTTP | `application`, `domain` |
| `infrastructure` | Adaptadores de persistência, cliente HTTP, agendamento, métricas | `application`, `domain` |

O agregado é, ao mesmo tempo, a entidade JPA: recebe apenas anotações de mapeamento (`@Entity`, `@Id`, `@Version`,
`@Enumerated`), sem setters e sem nenhuma dependência de Spring ou Hibernate.

## Consequências

- O domínio continua testável sem Spring (todos os testes de `domain` são JUnit puro).
- Evita uma segunda classe "entidade de persistência" e um mapper que só copiariam campos.
- O preço é o construtor `protected` sem argumentos exigido pelo JPA e os campos não `final`. Se o modelo de
  persistência divergir do modelo de domínio no futuro (ex.: tabelas separadas por status), o mapeamento passa para
  `infrastructure.persistence` sem afetar `application` ou `api`, porque ambos dependem apenas da porta.

## Alternativas consideradas

- **Domínio puro + entidade JPA separada + mapper:** mais isolamento, mas dobra o código sem ganho prático aqui.
- **Pacote por camada técnica sem portas** (controller → service → JpaRepository): mais simples, mas acopla regras
  de negócio ao Spring Data e dificulta testar as transições isoladamente.
