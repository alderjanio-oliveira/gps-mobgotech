# Homologação do Traccar 6.17 com Rota-Tech

O repositório definitivo `/home/alderjanio/personal-projects/traccar` usa a branch `mobgo/evolucao-6.17`, criada a partir da base de produção `44565ef81`, com a integração da tag oficial
`v6.17.0` (45ae7321fe56688ca26f2f5b62e6dbb7a4737562) e web correspondente
(09c926c245bb3e30d0f70f9541da4637e15496ee). As customizações funcionais e os scripts de deploy dessa base foram preservados. A web também usa `mobgo/evolucao-6.17`, baseada no seu fork
`https://github.com/alderjanio-oliveira/traccar-web-.git`. As personalizações já
existentes nesse fork foram preservadas, sem novo redesign.

## Preparar e verificar

Use JDK 21 e Node 22.13+ (validado com 22.23.3), ou Node 24 LTS. Execute:

```bash
bash tools/verify-rota-tech.sh
```

Nesta sessão, JDK e cache Gradle foram preparados em `/tmp/mobgotech-java21`
e `/tmp/mobgotech-gradle`. São temporários; prepare a toolchain permanente do
ambiente de build antes de automatizar o deploy. O backup das fontes anteriores
está em `/tmp/mobgotech-before-6.17-source.tar.gz`; ele não é backup do banco.

A integração preserva o histórico dos dois forks. Ao publicar pelo Git, envie
primeiro a branch web para `mobgo` e depois a branch do backend, cujo submódulo
referencia o commit web integrado. `origin` continua sendo o upstream oficial.
Nenhum push ou deploy foi executado nesta sessão.

## Contrato preservado para o app

- `GET /api/distancereminders?deviceId=ID`: regras acessíveis ao usuário e vinculadas ao veículo.
- `POST /api/distancereminders`: cria com `name`, `thresholdDistance` e `startValue` em metros. Meta positiva e referência não negativa; estado inicial controlado pelo servidor.
- `POST /api/permissions` com `deviceId` e `distanceReminderId`: vínculo entre regra e dispositivo. O criador recebe vínculo de usuário pelo CRUD nativo.
- `PUT /api/distancereminders/ID`: edita somente regra pendente. Regra notificada ou encerrada retorna 409; registrar uma nova regra para uma nova meta.
- `POST /api/distancereminders/ID/confirm`: encerra e guarda distância final. Repetição preserva data/distância; regra cancelada retorna 409.
- `POST /api/distancereminders/ID/cancel`: cancela. Repetição é idempotente; regra concluída retorna 409.
- Confirmação/cancelamento exigem propriedade e autorização de edição; readonly não pode alterar.
- Estados: `pending`, `notified`, `done`, `cancelled`. `notified` representa atingimento registrado, **não comprova entrega de e-mail ou push**.
- Evento `distanceReminder`; eventos de alimentação `chargeConnected` e `chargeDisconnected`. Selecionar os canais desejados nas notificações nativas.

O app existente já cria as regras e vínculos; não converter dados para
`attributes.trip`. Ambos os campos de distância estão em metros; a interface
converte para km. Não alterar o odômetro real ao confirmar uma meta.

## Lembrete salvo pelo app, mas sem notificação

Salvar `/api/distancereminders` e vinculá-lo ao dispositivo não cria uma regra
nativa de notificação. As preferências do Rota-Tech são locais no telefone e não
configuram automaticamente e-mail/Telegram no servidor. Verificar o fluxo com:

`GET /api/devices/DEVICE_ID/distancereminders/REMINDER_ID/diagnostics`

A consulta exige acesso ao dispositivo e ao lembrete e verifica o vínculo entre
eles. Retorna distância/meta, estado, último evento persistido, regras destinadas
ao usuário atual, canais configurados/habilitados e possíveis bloqueios:

- `noNotificationRuleForCurrentUser`: cadastrar uma notificação do tipo
  `distanceReminder`, selecionar os canais e vincular usuário/dispositivo (ou
  aplicar a todos os dispositivos do usuário pela opção nativa).
- `noEnabledNotificationChannel`: os canais da regra não estão habilitados no servidor.
- `reachedWithoutSavedEvent`: o registro está `notified`, mas não há evento salvo;
  investigar falha de persistência. Não alterar a meta ou o odômetro para esconder isso.
- `targetNotReached`, `odometerUnavailable`, `invalidThreshold`, `reminderClosed`:
  conferir a condição indicada antes de investigar entrega.

`deliveryStatus=unknown` é intencional: existir evento/regra/canal não comprova
entrega. Calendários, filtros adicionais, destinatário bloqueado, transporte e
respostas do provedor ainda precisam ser conferidos nos logs. Não há ativação
automática de canais nem envio de mensagens durante a consulta.

## Diagnóstico por dispositivo

`GET /api/devices/ID/diagnostics?staleSeconds=900` verifica a permissão de acesso e
retorna comunicação, datas/idades, validade/idade GPS, última ignição/alimentação
presentes na posição e estado de bloqueio. Os estados de posição são `unavailable`,
`invalid`, `futureTimestamp`, `stale` e `recent`. Velocidade em nós (`speedKnots`).
Ausência de atributo é `null`, não desligado. O limiar é positivo e afeta somente
a consulta, sem alterar a configuração do equipamento. `causeConfirmed=false`:
o diagnóstico não confirma hibernação, corte de energia ou causa de desconexão.

## Ambiente existente e responsabilidade de publicação

O procedimento anterior está em
`/home/alderjanio/personal-projects/traccar/scripts/deploy.sh`. O README desse fork
descreve produção no droplet `gps.mobgotech.com` (`167.99.126.116`), instalação
`/opt/traccar`, MySQL e systemd `traccar`; `--stg` usa `/opt/traccar-stg`, banco
`traccar_stg` e porta 8083 no mesmo droplet. Por padrão ele clona estrutura,
histórico Liquibase e configuração global, sem copiar posições/usuários.

O usuário assumiu o acesso e a atualização remota. A tentativa de inspeção foi
somente de leitura: a identidade do servidor foi validada pela chave conhecida
para o IP, mas a chave pública disponível foi recusada para root. Nenhum comando
remoto foi executado, nem banco ou serviço remoto alterado.

**Adaptar o script antigo antes de usá-lo com esta versão:** ele descreve migrations
como somente aditivas e faz rollback apenas de arquivos. A migration oficial
6.17 executa DELETE nos vínculos recíprocos de `tc_device_device`, e a 6.15 altera
tipos de colunas. Portanto, retorno exige estratégia de banco, além de binários.
O script antigo também faz DROP do banco de staging e remove seu diretório:
verificar se esse ambiente está em uso antes de reutilizar o mesmo nome.

Na homologação, desabilitar notificadores e forwarders externos, usar portas
separadas e impedir que protocolos GPS tentem ocupar as portas da produção.
Na 6.17, avaliar também `protocols.defaultTransport=DISABLED` na cópia do banco;
a configuração do pacote de revisão habilita somente GT06 em localhost:15023.
Não executar `--fix-changelog` em produção sem verificar o drift físico e o
histórico: o README antigo registra uma inconsistência específica em 6.13.

## Testes em ambiente independente

1. Restaurar backup consistente do banco em uma instância isolada. Não reutilizar
   a URL JDBC, diretório H2 ou volume da produção. Validar tipo/versão do banco real.
2. Validar migrations oficiais cumulativas e as quatro migrations do fork
   (6.14.0, 6.14.1, 6.14.2 e block-state). Os arquivos originais foram mantidos para
   preservar identidades/checksums. Os testes locais cobrem H2 novo e upgrade do schema anterior do fork; restauração
   do banco MySQL real e eventual drift de produção ainda precisam de ensaio.
3. Usar apenas rastreador e destinatários de teste. Na primeira inicialização,
   manter `notificator.types=web` para não enviar Telegram/e-mail/push aos clientes.
4. Para SMTP, conferir configuração efetiva e `GET /api/notifications/notificators`.
   A chave é `mail`. Configurar destinatário de teste e executar
   `POST /api/notifications/test/mail`. Não divulgar senha/token em relatórios.
5. Criar meta com referência zero e intervalo 1.000 m. Confirmar que 999 m não
   dispara e 1.000 m dispara. Repetir posição, reiniciar, simular falha de banco,
   confirmar/cancelar e criar nova meta. Conferir evento e resultado de cada canal.
6. Simular pacote inválido, distância negativa/não finita e salto GPS. Os filtros
   nativos de validade/velocidade/duplicação precisam ser calibrados com amostras
   reais. `filter.maxSpeed` usa **nós**, não km/h. Não ativar limites arbitrários
   globalmente nem recalcular odômetros históricos automaticamente.
7. Medir recebimento → socket → renderização com relógios sincronizados. Simular
   reconexão, visibilidade e retorno de snapshots antigos. A reconexão usa espera
   progressiva (~1–36 s); chamadas de recuperação têm timeout. Esse tempo não é
   promessa de latência ponta a ponta do rastreador.
8. Usar operador A/B para tentar listar/editar clientes, dispositivos, relatórios,
   eventos, comandos e vínculos do outro. Administrador global continua global;
   operador isolado deve ser gerente. A validação do lote antecede gravações, mas
   não há transação genérica para falhas de banco no meio do lote.
9. Bloqueio somente em dispositivo de bancada: envio não confirma estado físico.
   Conferir `blockState`, `blockRequestStatus`, telemetria tardia e falha de envio.

## Limitações e próximos incrementos

As correções locais não encerram o plano inteiro. Permanecem:

- Outbox/fila transacional e histórico persistente das tentativas por canal;
  retentativa/reconciliação após queda entre registro do atingimento e geração
  do evento. A proteção atual do cache trata falha de update, mas não resolve
  atomicidade distribuída nem esse intervalo de falha.
- Confirmar SMTP, associações e disponibilidade dos canais na instalação real.
  Logs agora correlacionam evento/regra/usuário/canal e isolam falhas de um canal.
  Sucesso do adaptador não significa entrega final ou leitura.
- Teste completo de isolamento entre operadores, incluindo HTTP e WebSocket.
- Política completa de alertas de silêncio/recuperação e painel de diagnóstico.
- Modelo financeiro, histórico de pagamentos e carência de dez dias separados
  da expiração nativa; ainda não implementados.
- Histórico completo de comandos, deduplicação e correlação de respostas.
- Dashboard, métricas J16, relatórios/auditoria ampliados e operações em lote.
- Publicação, backup restaurável e rollback ensaiado com o banco real.

## Publicação e retorno

A produção não foi alterada. Antes de publicar, preservar banco, configuração,
mídia e artefatos anteriores; testar restauração e migrations; escolher janela
conforme retransmissão do rastreador. Não executar duas versões escrevendo no
mesmo banco. Retorno após migration pode exigir restaurar banco e aplicação;
definir como reconciliar posições recebidas após o backup.

## Extensões integradas nesta pasta

- Fila persistente de notificações: evento e atingimento da meta na mesma transação; tentativas por destinatário/canal, recuperação após reinício e retentativas limitadas. Tela em Configurações → histórico de envios; API `/api/devices/{id}/notification-deliveries`. Aceite do adaptador não comprova entrega final.
- Financeiro opcional por usuário: `/api/users/{id}/billing`, pagamentos e histórico; carência padrão de dez dias completos após o vencimento no fuso configurado. Não migra contas automaticamente nem altera a ingestão GPS. Administrador/operador não pode modificar o próprio financeiro.
- Saúde opcional por dispositivo: atributos `monitoring.*`, painel `/operations/health`, incidentes persistentes, histerese e recuperação. Nenhuma inferência de causa de silêncio ou confirmação de hibernação.
- Comandos: cabeçalho `Idempotency-Key`, histórico `/api/devices/{id}/command-history`, distinção entre pedido, envio, resposta e telemetria de relé. Resultado incerto não autoriza repetição automática de comando físico.
- Permissões de sockets recalculadas após revogação direta, remoção de grupo ou movimentação do dispositivo; consultas iniciais de posições limitadas ao escopo nativo do usuário.

Migrations adicionais: `changelog-mobgo-notification-outbox.xml`, `changelog-mobgo-billing.xml`, `changelog-mobgo-health.xml` e `changelog-mobgo-command-ledger.xml`. Homologar com backup restaurado do MySQL antes de produção. Scripts existentes continuam sendo a referência de publicação; nesta sessão apenas builds e testes locais foram executados.
