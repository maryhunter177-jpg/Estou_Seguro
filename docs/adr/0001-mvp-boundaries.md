# ADR 0001 — Limites do primeiro MVP

**Status:** aceito em 2026-08-31

## Contexto

O produto atua em estado crítico. Um botão visualmente funcional que dependa de infraestrutura ainda inexistente pode gerar falsa sensação de segurança.

## Decisão

O MVP prepara e persiste um alerta, usa somente a última localização autorizada e transfere o envio para um aplicativo de mensagens, onde a pessoa confirma a ação. Não haverá SMS silencioso, rastreamento contínuo, alegação de entrega ou contato automático com autoridades nesta fase.

## Consequências

O fluxo pode exigir mais um toque, porém seu comportamento é verificável e compatível com as restrições da plataforma. Compartilhamento em tempo real só será liberado junto de backend observável, consentimento explícito, expiração automática e testes de falha.
