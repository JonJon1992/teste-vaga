# ADR 0004 — Segurança com OAuth 2.0 Client Credentials e JWT

**Status:** aceito

## Contexto

A API é consumida por outros sistemas, não por pessoas. É preciso autenticar o sistema chamador, separar quem cria de
quem só consulta e impedir que um sistema veja solicitações de outro (OWASP API1:2023 — BOLA).

## Decisão

- **Fluxo Client Credentials:** cada sistema consumidor é um client no servidor de autorização (Keycloak, Entra ID,
  Cognito etc.) e obtém tokens com `client_id` + segredo (ou `private_key_jwt`/mTLS). Não há usuário final nem
  redirecionamento, então Authorization Code não se aplica.
- **O serviço é um Resource Server** (Spring Security) e valida no JWT:
  - assinatura, com chaves públicas obtidas do JWKS do emissor (`JWT_JWK_SET_URI`, com cache e rotação automática
    pelo `kid`);
  - emissor (`iss` = `JWT_ISSUER_URI`), audiência (`aud` contém `JWT_AUDIENCE`) e validade (`exp`/`nbf`).
- **Autorização por escopo:** `activation:write` para criar e `activation:read` para consultar e listar; qualquer
  outra rota é negada.
- **Isolamento entre consumidores:** a claim `sub` (configurável por `JWT_PRINCIPAL_CLAIM`, ex.: `azp` ou
  `client_id` no Keycloak) é gravada como `client_id` da solicitação. Consultas e listagens filtram por ela; um id de
  outro consumidor responde `404`, e não `403`, para não confirmar que ele existe.
- **401/403** mantêm o header `WWW-Authenticate` do padrão Bearer e corpo Problem Details com `traceId`.

## Credencial do provedor externo

O serviço envia `Authorization: Bearer ${PROVIDER_API_KEY}` ao provedor. Em produção o valor vem de um gerenciador
de segredos (AWS Secrets Manager, Azure Key Vault, GCP Secret Manager ou Vault), injetado como variável de ambiente
ou arquivo montado, nunca no repositório ou na imagem. Para rotação sem indisponibilidade, o provedor aceita duas
chaves válidas por um período: a nova é publicada no gerenciador, as instâncias são reiniciadas de forma gradual (ou
releem o segredo) e só então a antiga é revogada. Se o provedor suportar OAuth, o ideal é o próprio serviço usar
Client Credentials com tokens de curta duração.

## Consequências

- Nenhum segredo de autenticação fica no serviço: só chaves públicas.
- Testes de 401/403 e de isolamento rodam sem servidor de autenticação (`spring-security-test` + `JwtDecoder` simulado).
- O serviço limita o tamanho do corpo (`413`) e a taxa por consumidor autenticado (`429` + `Retry-After`, token
  bucket por instância). O API gateway continua sendo a primeira barreira, inclusive para tráfego não autenticado.
