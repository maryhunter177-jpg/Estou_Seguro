# Checklist de qualidade da release Android

Este checklist e obrigatorio antes de entregar um novo APK para teste em aparelho fisico.
Ele cobre os fluxos que podem bloquear acesso ao aplicativo ou causar perda de dados durante uma atualizacao.

## Bloqueadores (P0)

- [ ] Gerar o APK com `versionCode` maior que o APK anterior e registrar `versionName` no changelog.
- [ ] Confirmar que o APK novo usa o mesmo `applicationId` e o mesmo certificado do APK instalado.
- [ ] Instalar a versao anterior, cadastrar nome, PIN e um contato; instalar a nova por cima, sem desinstalar.
- [ ] Confirmar que nome, PIN, contatos e historico continuam disponiveis apos a atualizacao.
- [ ] Confirmar desbloqueio por toque no botao e pela acao **Concluir** do teclado.
- [ ] Durante a validacao, exibir progresso, impedir segundo envio e restaurar a tela em sucesso ou erro.
- [ ] Testar PIN correto, PIN incorreto, menos de 4 digitos, mais de 8 digitos e caracteres nao numericos.
- [ ] Confirmar que uma falha interna de autenticacao mostra erro e permite nova tentativa.
- [ ] Confirmar que voltar ao app depois do tempo de bloqueio exige o PIN novamente.

## Fluxos criticos (P1)

- [ ] Abrir o atalho de emergencia e confirmar que ele inicia o fluxo pretendido depois da autenticacao.
- [ ] Negar localizacao pela primeira vez e permanentemente; o alerta deve continuar sem travar.
- [ ] Testar com localizacao desativada, sem localizacao anterior e com localizacao antiga.
- [ ] Testar sem aplicativo de SMS compativel e confirmar o fallback de compartilhamento.
- [ ] Testar cadastro, edicao, duplicidade e exclusao de contatos.
- [ ] Testar aparelho sem contatos: alerta e check-in devem explicar como corrigir o problema.
- [ ] Enviar o app ao segundo plano durante desbloqueio, carregamento, permissao e compartilhamento.
- [ ] Girar a tela durante os mesmos fluxos e verificar que nao ocorre duplicidade nem tela inconsistente.

## Seguranca e resiliencia (P1)

- [ ] Limitar tentativas consecutivas de PIN com atraso progressivo persistente.
- [ ] Nao registrar PIN, hash, salt, telefone ou localizacao em logs.
- [ ] Zerar copias mutaveis do PIN assim que a derivacao terminar.
- [ ] Validar comportamento com preferencias de credencial ausentes, parciais ou corrompidas.
- [ ] Garantir migracoes incrementais do SQLite antes de aumentar a versao do banco.
- [ ] Definir e testar politica explicita para captura de tela em telas sensiveis.

## Acessibilidade e interface (P2)

- [ ] Testar fonte em 200%, tema claro, contraste e leitor de tela.
- [ ] Garantir alvos de toque de pelo menos 48 dp e descricoes sem depender apenas de simbolos.
- [ ] Confirmar que o teclado nao cobre a acao principal e que o foco chega ao primeiro erro.
- [ ] Verificar telas em aparelhos pequenos e com nomes/telefones longos.

## Evidencias da entrega

- [ ] `testDebugUnitTest` sem falhas.
- [ ] `lintDebug` sem erros bloqueadores.
- [ ] Testes instrumentados de cadastro/desbloqueio e upgrade executados em ao menos um Android real.
- [ ] Hash SHA-256, certificado, `versionCode`, `versionName`, tamanho e data do APK registrados.
- [ ] Capturas das telas principais anexadas ao registro da release.
