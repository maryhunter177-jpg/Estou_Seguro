# Backend Estou Seguro

API Kotlin/Ktor com PostgreSQL e fila transacional para alertas pela API oficial do WhatsApp Business Cloud. O token da Meta permanece no servidor e nunca entra no APK.

## Fluxo do sandbox

1. Copie `.env.example` para `.env` e gere valores aleatórios fortes para os segredos.
2. No painel Meta for Developers, use primeiro o número de teste fornecido pela Meta e adicione os números destinatários permitidos.
3. Configure `META_PHONE_NUMBER_ID`, `META_ACCESS_TOKEN`, `META_APP_SECRET`, o token de verificação e o template aprovado.
4. Suba PostgreSQL e API com `docker compose up --build`.
5. Exponha a API por HTTPS e configure `/webhooks/whatsapp` como callback da Meta.

### Ativação de dispositivo no sandbox

`SANDBOX_REGISTRATION_KEY` é uma chave operacional e nunca deve ser incluída no APK. Um operador
emite um código descartável chamando `POST /ops/v1/activation-codes` com o header
`X-Sandbox-Registration-Key`. A resposta `201` contém `code` no formato `XXXX-XXXX-XXXX` e
`expiresAt`, limitado a 15 minutos.

O aplicativo então chama `POST /v1/devices/register`, envia seu JSON normal e inclui somente
`X-Sandbox-Activation-Code: XXXX-XXXX-XXXX`. O código não diferencia maiúsculas/minúsculas,
aceita espaços ou hífens de formatação, é armazenado apenas como HMAC e só pode ser consumido uma
vez. Código ausente, inválido, expirado ou já usado retorna `401 ACTIVATION_CODE_INVALID` sem
revelar qual condição ocorreu.

O arquivo `../render.yaml` oferece uma publicação sandbox por Blueprint no Render. Os planos gratuitos são adequados somente para desenvolvimento: podem suspender recursos e não oferecem a disponibilidade necessária para uma emergência real.

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
