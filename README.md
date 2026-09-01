# Estou Seguro

Aplicativo Android nativo de segurança pessoal, escrito em Kotlin. A versão atual entrega acesso local por PIN, SOS por pressão longa sem desbloqueio, contatos de confiança validados, alertas contextualizados, SMS sequencial por contato com acompanhamento técnico, última localização conhecida, ficha médica criptografada, check-in, atalhos de emergência e histórico local.

> **Importante:** este é um MVP em desenvolvimento. Ele não substitui serviços públicos de emergência e não pode garantir sinal, saldo, disponibilidade da operadora nem entrega. Para risco imediato no Brasil, use 190, SAMU 192 ou Bombeiros 193 conforme a ocorrência.

## Executar

Pré-requisitos:

- Android Studio com JDK 17;
- Android SDK 36;
- dispositivo ou emulador Android 8.0 (API 26) ou mais recente.

Abra a raiz no Android Studio, aguarde a sincronização e execute a configuração `app`. Na linha de comando, use `./gradlew testDebugUnitTest lintDebug assembleDebug` (macOS/Linux) ou `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug` (Windows).

## Arquitetura

```text
MainActivity (UI e orquestração Android)
        |
domain/usecase  — regras puras e testáveis
        |
domain/repository — contratos
        |
data/repository — SQLite, credencial e ficha médica criptografada
        |
Android framework — localização, SMS e compartilhamento
```

A aplicação usa um `AppContainer` explícito em vez de framework de injeção no primeiro incremento. As dependências ficam centralizadas e substituíveis em testes, com baixo custo de build. SQLite é acessado atrás de repositórios, permitindo migrar para Room sem alterar o domínio.

### Decisões de segurança

- PIN derivado com PBKDF2-HMAC-SHA256, salt aleatório e comparação em tempo constante;
- screenshots permitidos nesta versão de teste, conforme solicitado;
- localização solicitada apenas durante o alerta, sem rastreamento em segundo plano;
- SMS direto exige confirmação ou pressão longa no botão SOS e permissão explícita; a fila é sequencial e os callbacks da operadora são persistidos;
- WhatsApp abre a conversa do contato cadastrado com texto preenchido, mas ainda exige confirmação; automação real depende da API Business oficial e de backend;
- ficha médica protegida com AES-256-GCM e chave não exportável no Android Keystore;
- CPF não é coletado porque não é necessário para o primeiro atendimento;
- banco sem fallback destrutivo de migração;
- nenhum segredo, token ou chave embutido no app.

## Próximos incrementos

1. API autenticada (OIDC/passkeys), PostgreSQL com PostGIS e sessões revogáveis.
2. Sessão de trajeto e compartilhamento em tempo real via foreground service e backend, com aviso persistente, prazo de expiração e consentimento granular.
3. Backend de notificações com fila transacional, idempotência, confirmação de entrega, observabilidade e escalonamento.
4. Palavra-código e check-in agendado com modelo claro de ameaça e modo discreto revisado por especialistas.
5. Room, criptografia de dados locais sensíveis, exportação/exclusão LGPD e política de retenção.
6. Testes instrumentados de permissão/rotação/process death, acessibilidade, detekt/ktlint e pipeline de release assinado.

## Estrutura

- `domain/model`: entidades sem dependência do Android;
- `domain/usecase`: validação e regras da aplicação;
- `domain/repository`: portas para persistência;
- `data`: SQLite e credencial local;
- `platform`: localização e compartilhamento;
- `MainActivity`: UI Android do incremento inicial.
