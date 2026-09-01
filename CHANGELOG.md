# Changelog

## 0.3.0-debug — 2026-08-31

### Adicionado

- envio direto de SMS por contato, com mensagens multipartes e callbacks persistidos de envio/entrega;
- fallback para o aplicativo de mensagens e compartilhamento opcional pelo WhatsApp/outro app;
- modos de alerta geral, médico, roubo/sequestro e crise de ansiedade;
- ficha médica opcional com AES-256-GCM e chave não exportável no Android Keystore;
- atalhos de discagem para Polícia 190, SAMU 192 e Bombeiros 193;
- resumo do estado de entrega do alerta mais recente;
- logo ampliada e mensagem de marca no espaço inferior da tela de desbloqueio.

### Segurança e qualidade

- CPF deliberadamente não coletado; dados médicos mínimos, validados e removíveis;
- envio executado fora da thread principal e somente após confirmação e permissão explícitas;
- permissão de estado do telefone removida por não ser necessária ao fluxo atual;
- 18 testes unitários sem falhas e Android Lint com 0 erros;
- `versionCode` 4 e `versionName` 0.3.0-debug.

## 0.2.1-debug — 2026-08-31

### Adicionado

- controle acessível para mostrar ou ocultar o PIN no cadastro e desbloqueio;
- nova identidade visual aplicada ao ícone do aplicativo, atalho e cabeçalho.

### Verificação

- preservado o fluxo explícito de confirmação antes de abrir o aplicativo de mensagens;
- `versionCode` incrementado para 3.

## 0.2.0-debug — 2026-08-31

### Corrigido

- desbloqueio agora informa “Validando PIN…”, bloqueia cliques repetidos e mostra erros inline;
- ação **Concluir** do teclado também envia o PIN;
- credenciais parciais ou corrompidas não prendem mais o usuário na tela de desbloqueio;
- gravação inicial da credencial passou a ser confirmada antes de liberar o acesso;
- credenciais da versão 0.1.0 são migradas de forma compatível após autenticação;
- contrato de permissão da última localização foi endurecido e validado pelo Android Lint;
- atalho de emergência passa pelo desbloqueio e abre a confirmação correta;
- o aplicativo volta bloqueado depois de ir para segundo plano.

### Melhorado

- feedback de autenticação acessível para leitores de tela;
- PIN limitado a 4–8 dígitos ASCII de forma consistente entre teclados;
- regras explícitas impedem backup e transferência de dados sensíveis;
- `versionCode` incrementado para permitir upgrade rastreável sobre a versão anterior.

### Verificação

- 10 testes unitários sem falhas;
- Android Lint: 0 erros;
- APK assinado e verificado com Android APK Signature Scheme v2.
