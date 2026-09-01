# Backend Estou Seguro

API Kotlin/Ktor com PostgreSQL e fila transacional para alertas pela API oficial do WhatsApp Business Cloud. O token da Meta permanece no servidor e nunca entra no APK.

## Fluxo do sandbox

1. Copie `.env.example` para `.env` e gere valores aleatórios fortes para os segredos.
2. No painel Meta for Developers, use primeiro o número de teste fornecido pela Meta e adicione os números destinatários permitidos.
3. Configure `META_PHONE_NUMBER_ID`, `META_ACCESS_TOKEN`, `META_APP_SECRET`, o token de verificação e o template aprovado.
4. Suba PostgreSQL e API com `docker compose up --build`.
5. Exponha a API por HTTPS e configure `/webhooks/whatsapp` como callback da Meta.

Com `WORKER_ENABLED=false`, a aplicação e os testes funcionam sem credenciais de envio. Ative o worker somente depois de configurar o sandbox da Meta.

## Garantias implementadas

- token do dispositivo armazenado somente como HMAC;
- contatos brasileiros normalizados em E.164 e mascarados nas respostas;
- consentimento individual e de uso único antes do primeiro alerta;
- `Idempotency-Key` evita alertas duplicados;
- uma entrega independente por contato autorizado;
- fila PostgreSQL com `SKIP LOCKED`, retentativas limitadas e backoff;
- validação HMAC do webhook e deduplicação de eventos;
- estados `ACCEPTED`, `SENT`, `DELIVERED`, `READ` e `FAILED` registrados no banco;
- erros externos sanitizados, sem expor tokens ou telefones nos logs.

## Limites antes de produção

O cadastro aberto existe apenas no sandbox e exige `X-Sandbox-Registration-Key`. Produção ainda precisa de autenticação de usuário, rate limit no gateway, revogação de consentimento, painel operacional, monitoramento, backup, testes integrados com a Meta e revisão jurídica/LGPD. O serviço não substitui 190, 192 ou 193 e não pode prometer entrega quando internet, Meta ou aparelho destinatário estiverem indisponíveis.
