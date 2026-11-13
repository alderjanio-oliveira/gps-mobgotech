# Plano de execução — evolução do Traccar

Data: 12/11/2026. Escopo: servidor Java, web React e contrato com o aplicativo Flutter **Rota-Tech**. Este documento registra uma auditoria inicial do código local; não representa diagnóstico confirmado da produção.

## 1. Diretrizes e resultado esperado

- Preservar o Traccar como motor GPS e aproveitar recursos nativos antes de criar extensões.
- Manter o visual padrão do Traccar, sem identidade visual ou redesign baseado na MobgoTech. Melhorias funcionais de indicadores, estados e acessibilidade seguem os componentes existentes.
- Priorizar entrega confiável de eventos, quilometragem correta, isolamento entre operadores e continuidade da coleta GPS.
- Evoluir o aplicativo em uma etapa posterior. Nesta etapa ele foi consultado, sem alterações.
- Trabalhar em incrementos revisáveis, com migrations Liquibase quando necessárias e testes de comportamento em regras críticas.
- Não substituir produção antes de backup restaurável, homologação e plano de retorno.

## 2. Auditoria inicial e evidências (antes da execução)

### Versão e atualização

- Branch local: `improviment`; HEAD `7f7bb91094b71cf9363e816eeec02cc56f92e692`.
- Backend declara `6.12.2`, mas contém 19 commits após a tag: `v6.12.2-19-g7f7bb9109`. Portanto, não é uma instalação pura daquela versão.
- Web: `6.12.2`, commit `7b628809b945502742ff28c93e3753a40b2ea8be`.
- A comparação `git log v6.17.0..HEAD` identificou um commit exclusivo local: `7f7bb9109 notification`. Ele adiciona `ChargeEventHandler`, registra o handler e inclui configuração, banco e logs no Git. Preservar a lógica necessária, sem transportar dados de execução para a nova distribuição.
- Versão oficial consultada: **6.17.0**, publicada em 01/11/2026. Tag confirmada no remoto: `45ae7321fe56688ca26f2f5b62e6dbb7a4737562`.
- Web correspondente à tag: `09c926c245bb3e30d0f70f9541da4637e15496ee`. Usar esse commit, sem atualizar o submódulo arbitrariamente para master.
- A 6.17 requer Java 21; o ambiente atual usa Java 17.0.2 e Node 18.19.1. Para a web com Vite 8/ESLint 10, adotar Node 24 LTS e validar os engines do lockfile.
- Tag baixada e candidato exportado em `/tmp/mobgotech-traccar-6.17.0-review`, com backend e web correspondentes e porte inicial do handler de alimentação. Esse diretório é temporário, não é distribuição homologada nem branch definitiva.
- O upstream removeu `PositionUtil.isLatest`; o candidato usa `BasePositionEventHandler`. Compilação, estilo e testes ainda pendentes. Não houve atualização do checkout principal nem da produção.

### Notificações e quilometragem no Rota-Tech

Aplicativo atual confirmado pelo usuário: **Rota-Tech**, localizado em `/home/alderjanio/personal-projects/rota-tech`. Não confundir com o projeto Vue em `/home/alderjanio/rota-tec`. A análise anterior do `lock_master` descrevia um app antigo e não deve orientar a implementação atual.

Evidências em `rota-tech/lib`:

| Arquivo | Comportamento observado | Implicação |
| --- | --- | --- |
| `data/distance_reminder_model.dart` | Modelo `DistanceReminder`, com `thresholdDistance`, `startValue`, status e datas; distância percorrida = acumulado atual menos referência | Referência e meta em metros; estados `pending`, `notified`, `done`, `cancelled`; atingimento em 100% |
| `app/services/traccar_service.dart` | Usa `/api/distancereminders`; vincula via `/api/permissions` com `deviceId` e `distanceReminderId`; confirma/cancela via POST | O app já tem contrato próprio; não recriar a regra com `attributes.trip` ou manutenção incompatível |
| `core/services/work_manager_service.dart` | Verifica `isEnabled`, `tripAlert`, preferências de silêncio e login automático | As preferências já são respeitadas neste fluxo; a execução local ainda depende do telefone e da autenticação |
| Mesmo worker e `utils/constants.dart` | Agenda nova verificação em 24 horas, ou em 1 hora quando faltam até 200 km | Existe cadência adaptativa; não é correto descrever o app atual como exclusivamente diário |
| Mesmo worker e `core/services/app_notification_store.dart` | Registra notificações locais e conjunto de alertas ativos por dispositivo | Já há deduplicação local; auditar troca de lembrete, falhas parciais e sincronização entre aparelhos |
| Mesmo worker | Seleciona o primeiro lembrete pendente, incluindo status `notified` | Definir multiplicidade e ordenação; distinguir estado do lembrete de entrega real do canal |

O modelo do app referencia um fork chamado `gps-mobgotech`, a tabela `tc_distance_reminders` e a classe Java `DistanceReminder`. **Esses símbolos e o endpoint não foram encontrados neste checkout do Traccar.** Portanto, há divergência entre o backend esperado pelo Rota-Tech e o repositório atualmente aberto. Localizar a fonte/branch e as migrations desse backend é pré-requisito para integrar a atualização sem perder a funcionalidade.

Conclusão: o Rota-Tech já possui lembretes próprios, preferências, atingimento em 100%, histórico e controle local de repetição. Ainda é necessário auditar a geração e entrega dos eventos no backend efetivamente usado; o código do app não permite concluir que o servidor deixa de gerar esses eventos.

### Recursos existentes no servidor

| Funcionalidade | Já existe? | Constatação / investigação restante | Solução planejada | Risco |
| --- | --- | --- | --- | --- |
| E-mail | Sim | `NotificatorMail` e `NotificatorManager`; chave de canal é `mail`. Config local contém apenas banco; SMTP pode estar em atributos/configuração externa | Diagnosticar configuração efetiva, endpoint de canais, associações, templates e transporte | Médio |
| Quilometragem | Parcial | `MaintenanceEventHandler` compara valores e períodos; não implementa o contrato `/api/distancereminders` esperado pelo Rota-Tech | Localizar e preservar o DistanceReminder do backend do Rota-Tech; auditar evento e entrega antes de criar extensão | Médio |
| GPS/odômetro | Sim | `DistanceHandler` e `FilterHandler`; opções de filtragem não demonstram que estão ativas em produção | Reproduzir saltos e ajustar filtros antes de confiar em metas | Alto |
| Tempo real | Sim | `AsyncSocket`, `ConnectionManager`, `SocketController.jsx` | Medir cada etapa antes de animar ou alterar transporte | Médio |
| Estado desconhecido | Sim, limitado | Status de conexão e atributos de posição são informações distintas | Diagnóstico com idade dos dados, evidências e tolerância | Médio |
| Multioperador | Parcialmente nativo | `PermissionsService`, usuários gerenciados e relações em `PermissionsResource` | Validar gerente com escopo; reservar administrador global ao superadmin | Alto |
| Bloqueio e respostas | Parcial | `CommandsManager`, `CommandResource`, `CommandResultEventHandler` | Correlacionar solicitação, envio, resposta e evidência física | Alto |
| Expiração | Sim | `TaskExpirations` envia avisos; não é um módulo financeiro | Separar cobrança e suspensão de acesso | Alto |
| Auditoria | Sim | `AuditResource`, `LogAction`, relatório web | Avaliar cobertura, retenção e isolamento antes de ampliar | Médio |
| Relatórios | Sim | Relatórios e históricos nativos | Medir consultas, escopo, limites e exportações | Médio |
| Saúde, dashboard e operações em lote | Parcial / a detalhar | Eventos, posições e comandos fornecem parte dos dados | Agregar métricas e executar operações auditáveis por escopo | Médio |

Não foram acessados servidor remoto, SMTP real ou banco de produção. A configuração local não permite concluir por que o canal e-mail desaparece na instalação publicada.

## 3. Sequência de execução e entregas

Cada tarefa deve resultar em alteração pequena, evidência de validação e atualização deste plano. Dependência geral: atualização homologada → estabilidade → experiência → gestão → diagnóstico operacional. O diagnóstico dos incidentes pode avançar durante a preparação da atualização.

### Ciclo 0 — Base atualizada e ambiente reproduzível

**U01 — Inventário e baseline.** Registrar versão realmente instalada, SO, Java, proxy, banco, tamanho/volume de dados, protocolos/portas, configuração efetiva e caminhos de dados. Localizar o backend/fork `gps-mobgotech` usado pelo Rota-Tech e confirmar sua versão publicada. Medir baseline de ingestão, WebSocket, notificações e uso de recursos. Sanitizar registros; não registrar segredos.

**U02 — Atualização 6.17.0.** Criar branch de atualização a partir da tag confirmada; fixar o submódulo indicado. Portar apenas customizações necessárias, incluindo alimentação e o módulo DistanceReminder do backend efetivo. O candidato temporário atual ainda não inclui esse módulo. Revisar mudanças de API, GT06/J16, notificações, Firebase, permissões e schema. Não fazer cherry-pick integral do commit local, pois inclui banco e logs.

**U03 — Toolchain e build.** Preparar Java 21 e Node 24 em ambiente isolado. Executar `./gradlew test checkstyleMain checkstyleTest assemble` e, na web, `npm ci`, `npm run lint`, `npm run build`. Revisar falhas e mudanças do lockfile; produzir artefatos versionados. Esses comandos ainda não foram executados para o candidato.

**U04 — Migrations e regressão.** Restaurar backup em banco independente; aplicar migrations cumulativas oficiais; validar usuários, permissões, lembretes e seus vínculos/status/histórico, posições, eventos e comandos. Repetir a inicialização para verificar consistência. Usar rede e portas isoladas, sem notificações reais ou comandos em veículos de clientes.

**Aceite:** build e verificações passam, dados preservados, um J16 de teste transmite corretamente, web autentica/recebe posições e o app atual continua compatível. Backup e restauração ensaiados. Candidato atual ainda não atende esse gate.

### Ciclo 1 — Estabilidade: notificações, distância e alimentação (P0)

**N01 — E-mail ponta a ponta.** Verificar `/api/notifications/notificators`, `notificator.types`, seleção `mail` na `NotificationPage.jsx`, endereço do destinatário e configuração SMTP efetiva. O padrão local do código é `web,mail,command`; não assumir que falta habilitar `mail`. Testar autenticação, TLS, template e associações usuário/dispositivo/notificação. Usar `/api/notifications/test/mail` apenas em homologação e com destinatário de teste autorizado.

**N02 — Rastreabilidade de entrega.** Modelar tentativa por evento, destinatário e canal, com horários, resultado, erro sanitizado e correlação. Distinguir evento persistido, envio solicitado, aceito pelo SMTP e entrega confirmada pelo provedor quando houver retorno. Para web, distinguir envio ao socket de confirmação de leitura. Implementar retentativas limitadas para erros transitórios e idempotência; avaliar fila transacional/outbox para sobreviver a reinícios. SMTP não oferece garantia universal de entrega exatamente uma vez: tratar resultado incerto explicitamente.

**K01 — Contrato da meta existente.** Preservar o contrato Rota-Tech: `startValue` e `thresholdDistance` em metros; meta relativa atingida quando `totalDistance - startValue >= thresholdDistance`. Exemplo: referência de 15.000 km + intervalo de 1.000 km = odômetro de 16.000 km. Preservar estados e datas de `DistanceReminder`, vínculo ao dispositivo e ações de confirmação/cancelamento. Documentar destinatários, propriedade, origem do odômetro e política para múltiplos lembretes. Meta absoluta e recorrência são ampliações futuras, sem alterar o significado da regra atual.

**K02 — Auditar o backend correto.** Localizar código, migrations e versão do fork `gps-mobgotech` que fornece `/api/distancereminders`; comparar com este checkout e com a 6.17 antes de integrar. Verificar criação, vínculo, cálculo, transição `pending → notified`, evento, destinatários e envio. Não substituir a funcionalidade por manutenção nativa sem demonstrar equivalência. Persistir o atingimento com proteção contra concorrência/replay e separar isso do resultado de cada canal. Testar odômetro zero, cadastro já vencido, correção de odômetro e reinício.

**K03 — Compatibilidade e entrega no Rota-Tech.** Preservar listagem por dispositivo, criação, vínculo via permissions, confirmação e cancelamento, bem como histórico dos lembretes existentes. Testar a criação seguida de falha no vínculo para evitar órfãos. Auditar o worker adaptativo (24h/1h próximo da meta), seleção `pending.first` e deduplicação por dispositivo; preferir identificar lembrete/revisão no controle de repetição. Na etapa futura do app, integrar eventos/push e evitar duplicidade com a notificação local existente. Preservar preferências e silenciamentos, sem depender do telefone para gerar o evento no servidor.

**K04 — Integridade da distância.** Reproduzir posição no oceano, salto, velocidade implausível, fix inválido, coordenadas repetidas, relógio fora de ordem e duplicação de pacotes. Verificar a ordem de filtros e cálculo antes de ajustar limites por dispositivo/protocolo. Comparar odômetro do equipamento, acumulado GPS e valor corrigido manualmente. Guardar origem e trilha de correções; não recalcular histórico de clientes automaticamente. Testar também trajetos válidos longos após perda de sinal para evitar descarte excessivo.

**N03 — Alimentação.** Testar corte e restauração, ausência de `charge`, pacotes antigos e alarmes explícitos GT06. Conciliar o `ChargeEventHandler` customizado com `AlarmEventHandler` para não duplicar o mesmo incidente. Substituir impressão em stdout por logging apropriado, caso necessário. Não inferir falha física apenas por desconexão.

**Aceite e testes:** meta de 1.000 km não dispara em 999,9 km; dispara ao atingir ou ultrapassar a meta; pode partir de odômetro zero; reinício/replay/concorrência não duplicam atingimento; nova meta permite novo aviso. Meta inativa e preferências de canal são respeitadas. Evento chega aos canais configurados com resultado auditável; falha SMTP aparece no diagnóstico e segue a política de retentativa. Não depender de app aberto, Workmanager ou login automático para gerar o evento no servidor.

### Ciclo 2 — Monitoramento, estado e tempo real (P0/P1)

**R01 — Medição.** Correlacionar horário de fix, recebimento (`serverTime`), persistência, publicação no socket e renderização. Medir intervalo real do J16 e distinguir latência de envio de latência interna. Investigar proxy, timeouts, buffering, perda/reconexão e sincronização dos relógios. Não prometer posições a cada segundo se o equipamento envia a cada 15 segundos.

**R02 — Atualização e reconexão.** Corrigir o gargalo medido em `ConnectionManager`, `AsyncSocket` ou `SocketController.jsx`. Recuperar estado após reconexão sem duplicar eventos nem permitir regressão de posição. Proposta inicial de aceite: p95 de recebimento no servidor até exibição web ≤ 2 s em homologação com carga representativa; confirmar meta com o baseline e medir o app separadamente.

**R03 — Mapa funcional.** Orientar por course, permitir acompanhamento/centralização, exibir velocidade e idade da última posição. Interpolação curta, cancelável e identificada como animação, sem extrapolação como fato. Desativar para dados antigos, saltos e pontos inválidos; respeitar redução de movimento do navegador.

**S01 — Diagnóstico de estado.** Manter eixos separados: conexão, idade/validade GPS, ignição, movimento, alimentação e bloqueio. Exibir estado atual ou último conhecido com timestamp. `Possível hibernação` deve ser hipótese identificada; dados insuficientes não confirmam a causa. Definir limites por dispositivo/cliente e reutilizar eventos online/offline existentes.

**S02 — Tolerância e recuperação.** Alertas configuráveis por falta de comunicação, offline prolongado, última ignição ligada, GPS indisponível, alimentação e recuperação. Implementar máquina de estados com histerese, incidente persistente e recuperação única. Testar flapping, reinício e retorno com dados antigos. Distinguir recebimento de heartbeat de posição GPS válida.

**V01 — Indicadores sem redesign.** Usar componentes e tema padrão: comunicação/movimento como estado principal e alimentação/bloqueio/alerta como indicadores secundários. Cores e símbolos devem ter legenda e texto acessível; não depender apenas de cor. Validar combinações como offline com última ignição ligada e bloqueio solicitado sem confirmação. Harmonizar a semântica com o Flutter na etapa futura.

**Aceite:** marcador atualiza sem recarregar, reconexão recupera o último estado, timestamps são claros e o diagnóstico informa incerteza. Simular silêncio, conexão sem GPS, offline e recuperação. Toda mudança visual mantém o padrão Traccar.

### Ciclo 3 — Gestão, permissões, comandos e cobrança (P1)

**P01 — Matriz de acesso.** Usar superadmin global e gerentes de usuários com escopo nativo. Não conceder `administrator=true` a operadores isolados: no código atual `PermissionsResource` dispensa a checagem de propriedade para administradores globais. Auditar criação e edição de usuários, grupos, dispositivos, vínculos, comandos, notificações, relatórios e exportações.

**P02 — Testes de isolamento.** Montar operador A/cliente A/dispositivo A e operador B/cliente B/dispositivo B. Testar IDs forjados, permissões simples e bulk, remoção de vínculos, WebSocket, relatórios, eventos, manutenção e comandos. A deve receber negação e nenhum dado de B; superadmin deve administrar ambos. Validar limites de criação e transferência sem permitir autopromoção. Se houver lacuna real, propor extensão mínima e migration, após documentar a falha reproduzível.

**C01 — Ciclo do comando.** Persistir solicitante, dispositivo, comando, chave idempotente, horários, estado de fila/envio, resposta bruta sanitizada, timeout e correlação. Distinguir solicitado, enfileirado, enviado, resposta recebida, execução adiada, falha e estado físico confirmado por telemetria compatível. `RELAY 1 OK` não comprova corte físico; `executed after speed < 20km/h` indica execução condicionada.

**C02 — Segurança operacional.** Preservar proteção do protocolo contra bloqueio em movimento; validar velocidade e idade da telemetria, permissões e comandos concorrentes. Evitar retentativa automática de resultado ambíguo e duplicação de bloqueio/desbloqueio. Testar offline, resposta atrasada, reconexão e resposta sem correlação; nunca confirmar fisicamente sem evidência.

**B01 — Modelo comercial.** Separar vencimento financeiro, carência configurável (padrão proposto: 10 dias), suspensão efetiva, status e histórico de pagamento. Definir cobrança por cliente/contrato e fuso do vencimento. Especificar: dias 1 a 10 após vencimento com acesso e aviso; a partir do dia 11 suspensão conforme política; pagamento confirmado reativa de forma idempotente. Se a regra precisar de horário exato, fixá-lo antes da migration.

**B02 — Acesso e coleta.** Aplicar suspensão ao login/sessões/API do cliente conforme política documentada, inclusive sockets já abertos. Manter ingestão e histórico GPS. Não usar expiração do dispositivo como mecanismo financeiro sem verificar impacto na recepção. Migrar expirações atuais sem suspensões inesperadas; preservar suspensão administrativa independente de pagamento.

**Aceite:** suíte de isolamento passa em todos os canais; operações proibidas não produzem efeito parcial. Histórico de comando representa incertezas e protege ações duplicadas. Testes de cobrança cobrem limites dos dias 10/11, fuso, reconciliação duplicada, suspensão administrativa e continuidade da ingestão.

### Ciclo 4 — Operação, diagnóstico e inteligência (P2)

**D01 — Saúde J16 por dispositivo.** Página com última comunicação, posição/fix válido, ignição, alimentação, velocidade, desconexões em 24h, eventos e comandos pendentes. Inferências devem indicar evidência e confiança, sem afirmar a causa de silêncio. Detectar desconexões frequentes e posições anormais com janela e limites configuráveis.

**D02 — Dashboard operacional.** Agregar dispositivos sem comunicação/GPS, incidentes de alimentação, notificações com falha, comandos pendentes e contratos em carência/suspensão. Respeitar escopo do operador, paginação, retenção e limites de consulta. Priorizar ações úteis de suporte, mantendo o tema padrão.

**A01 — Auditoria.** Avaliar cobertura nativa de criação/alteração/exclusão, vínculos, metas, correções de odômetro, comandos e pagamentos. Registrar ator, objeto, instante e alterações relevantes com dados sensíveis reduzidos. Impedir acesso cruzado e alteração por usuários comuns; definir retenção e exportação.

**H01 — Histórico e relatórios.** Validar rotas, viagens, paradas, distância, eventos e comandos; conferir fuso, unidades e consistência com odômetro. Medir consultas em cópia com volume real, revisar índices via migration quando necessário, limitar intervalos e validar exportações. Evitar divergência de distância entre web e app.

**O01 — Operações em lote.** Definir quais alterações serão permitidas (configuração, vínculo ou comando); não assumir atualização de firmware J16. Incluir prévia de dispositivos afetados, permissões por item, limite de taxa, resultado individual, auditoria e cancelamento de itens ainda não enviados. Comandos físicos seguem as mesmas proteções do fluxo individual.

**Aceite:** métricas reproduzíveis a partir dos dados, consultas suportam carga representativa, isolamento validado e suporte consegue rastrear um incidente da posição até o resultado do canal/comando.

## 4. Impacto técnico esperado

| Área | Arquivos / pontos de entrada | Persistência / API |
| --- | --- | --- |
| Atualização | `build.gradle`, wrapper, submódulo web, `ProcessingHandler`, handler de alimentação | Migrations oficiais cumulativas em `schema/` |
| Alertas | `notification/NotificatorManager`, `database/NotificationManager`, `notificators/NotificatorMail`, implementação de `mail/MailManager`, `NotificationResource`, templates | `/api/notifications`, `/api/notifications/notificators`, testes por canal; extensão de tentativas/outbox a definir |
| Metas | `DistanceReminder` e recurso/handler a localizar no fork; `MaintenanceEventHandler`, `DistanceHandler`, `FilterHandler` como referências nativas | `/api/maintenance`, vínculos em `/api/permissions`, `tc_distance_reminders` e vínculos do fork efetivo; `/api/distancereminders`, confirmação/cancelamento; nomes e migrations de vínculos a conferir |
| Tempo real / estados | `AsyncSocket`, `ConnectionManager`, `SocketController.jsx`, camada de mapa e eventos de conexão | `/api/socket`, eventos/posições nativos; possível estado persistente de incidente |
| Isolamento | `PermissionsService`, `PermissionsResource`, recursos de usuários/dispositivos/grupos/comandos | Relações nativas e endpoints individuais/bulk; evitar novo RBAC sem necessidade comprovada |
| Comandos | `CommandsManager`, `CommandResource`, `CommandResultEventHandler`, decoder GT06 | `/api/commands`; complementar histórico/correlação se recursos nativos não bastarem |
| Cobrança | autenticação, sessões, `TaskExpirations`, telas de cliente | Novo modelo financeiro versionado; contrato de API a definir antes de implementar |
| Operação | `AuditResource`, `LogAction`, relatórios web e recursos de eventos | APIs nativas primeiro; novos endpoints agregados apenas para lacunas medidas |

São pontos de investigação/impacto, não uma autorização para modificar todos esses arquivos. O schema e endpoints das extensões devem ser definidos após o teste dos recursos nativos, com contratos retrocompatíveis para o app.

## 5. Homologação, publicação e rollback

1. Inventariar serviço, portas GPS, proxy e banco realmente usados; não presumir que a cópia local é produção.
2. Gerar backup consistente do banco, configuração, mídia e artefatos atuais; registrar versões e verificar restauração. Para H2, usar procedimento consistente com o serviço parado ou mecanismo suportado, não copiar arquivo ativo arbitrariamente.
3. Homologar 6.17 + customizações em banco/restauração independentes, saídas SMTP/push isoladas e dispositivo de teste. Validar migrations e tempo de aplicação antes de escolher janela de manutenção.
4. Definir janela conforme comportamento de retransmissão dos rastreadores. Não direcionar duas versões para escrever simultaneamente no mesmo banco. Documentar eventual intervalo sem recepção; não prometer interrupção zero.
5. Publicar artefato identificado, backup final e plano de retorno prontos. Conferir recepção J16, sessões, socket, notificações, permissões e filas após a troca. Observar um ciclo operacional completo antes de encerrar a atualização.
6. Se houver falha de ingestão, corrupção, vazamento de escopo ou regressão crítica, retornar aplicação **e banco compatível**. Reverter apenas o binário após migration pode falhar. Planejar a preservação/reconciliação das posições recebidas após o backup e explicitar o RPO antes de publicar.

Critérios de liberação: builds/checks concluídos, testes críticos aprovados, migração e restauração ensaiadas, nenhuma falha de isolamento, contratos atuais do app funcionando e monitoramento ativo. A publicação será uma etapa posterior com alvo e resultado de homologação concretos.

## 6. Ordem do backlog e estimativa inicial

| Ordem | Entrega | Dependência | Estimativa de esforço inicial |
| --- | --- | --- | --- |
| 1 | U01–U04: atualização homologada | Acesso aos dados operacionais e toolchain | 3–6 dias úteis |
| 2 | N01–N03 e K01–K04: estabilidade | Baseline; validar correções na 6.17 | 5–10 dias úteis |
| 3 | R01–R03, S01–S02, V01: monitoramento | Eventos e distância confiáveis | 5–8 dias úteis |
| 4 | P01–P02, C01–C02, B01–B02: gestão | Modelo e isolamento validados | 8–15 dias úteis |
| 5 | D01–D02, A01, H01, O01: operação | Dados auditáveis dos ciclos anteriores | 6–10 dias úteis |

Faixas de esforço para uma pessoa, não prazo contratado; recalibrar após U01 e reprodução dos incidentes. A evolução Flutter é um pacote posterior, com estimativa própria. Isolamento P01 pode ser auditado cedo; qualquer vazamento confirmado sobe para P0.

## 7. Execução iniciada — situação atual

Destino confirmado pelo usuário: `/home/alderjanio/traccar`, com backend e web na branch `mobgo/evolucao-6.17`, baseada em `mobgo/master`. `origin` permanece como upstream oficial. O backend efetivo do Rota-Tech foi localizado em `/home/alderjanio/personal-projects/traccar` (commit `44565ef81`). As constatações da seção 2 registram o estado anterior à execução.

### Entregue no código local

- [x] Backend atualizado para fontes oficiais 6.17.0; web oficial correspondente integrada ao fork web existente.
- [x] Customizações funcionais reconciliadas com o backend do Rota-Tech: lembretes, alimentação e estado de bloqueio. Histórico e personalizações existentes do fork web preservados; sem novo redesign.
- [x] Migrations originais do fork preservadas e adicionadas ao encadeamento oficial, incluindo vínculos usuário/dispositivo e coluna de evento.
- [x] `DistanceReminder` registrado na lista explícita de permissões da 6.17 (mudança necessária após a remoção do scanner automático upstream).
- [x] Referência de odômetro zero aceita; falha de gravação não consome a regra no cache; posições antigas/arquivadas e valores não finitos ignorados.
- [x] Metas inválidas rejeitadas; regras notificadas/encerradas não podem ser rearmadas pelo PUT genérico.
- [x] Confirmação/cancelamento respeitam readonly e propriedade, preservam histórico na repetição e rejeitam transição terminal conflitante.
- [x] Distância GPS não acumula a partir de coordenadas inválidas; valores reportados negativos/não finitos não contaminam o acumulado.
- [x] Operações bulk validam todos os acessos e ciclos de vínculos antes de gravar. Falhas de banco durante gravações ainda não têm transação genérica.
- [x] Falha de canal desabilitado não impede outros canais; logs correlacionam evento, notificação, usuário e canal. Resultado do adaptador não é confirmação de entrega final.
- [x] Reconexão web progressiva e independente da recuperação HTTP; socket antigo/snapshot atrasado não deve regredir posição; cleanup impede reconectar após logout.
- [x] Endpoint protegido `/api/devices/{id}/diagnostics`, com comunicação, idade/validade GPS, últimos atributos presentes e bloqueio, sem afirmar causa de silêncio.
- [x] CI Java ajustado para 21; ferramentas de verificação e empacotamento adicionadas.

### Reconciliação dos forks — 13/11/2026

- Remotos `mobgo` configurados neste checkout e na web, preservando `origin` oficial.
- Backend e web na branch local `mobgo/evolucao-6.17`, baseada nos respectivos `mobgo/master`, com merge oficial 6.17.
- Módulo e registros de quilometragem do app mantidos; nenhuma recriação de lembretes ou alteração do banco real.
- Histórico e visual já existentes no fork web preservados, sem novo redesign.
- Novo diagnóstico de lembrete em `/api/devices/{deviceId}/distancereminders/{id}/diagnostics`, com acesso limitado ao dispositivo/lembrete e regras destinadas ao usuário atual.
- Distinção documentada entre lembrete salvo pelo app e regra nativa de notificação. O app atual não cria essa regra ao salvar uma meta; suas preferências ficam no telefone.
- Alterações anteriores preservadas em stashes de ambos os repositórios e no backup `/tmp/mobgo-before-rebase-20261113.tar.gz`.
- Resultado: 659 testes Java, checkstyle, lint/build web e teste JS passaram. Relatório em `docs/operations/2026-11-13-validation.json`.
- Commits locais preparados para revisão. Push e publicação não realizados.

### Verificação e entrega

A validação cobre testes Java, checkstyle de produção/teste, geração do JAR, teste JS de atualização ao vivo, lint e build da web. Os testes de schema cobrem instalação nova, reinicialização e upgrade a partir do schema anterior do fork, preservando lembretes/vínculos/histórico em H2; não substituem ensaio com restauração do MySQL real.

Instruções, contratos e limitações: [`docs/operations/rota-tech-6.17-homologacao.md`](../operations/rota-tech-6.17-homologacao.md). Pacote de revisão gerado por `python3 tools/package-rota-tech.py` em `target/rota-tech-traccar-6.17.0-review.zip`; inclui somente dependências referenciadas pelo JAR, schema, templates, web e configuração local isolada, sem banco/logs/credenciais da instalação atual.

### Pendências do plano completo

- [ ] Homologar com restauração do banco real, SMTP/Telegram/push e rastreador de bancada; medir latência e uso de recursos. Ambiente existente identificado nos scripts (staging no mesmo droplet, MySQL separado). Usuário assumiu o acesso e a atualização remota.
- [ ] Outbox/fila, retentativas e trilha persistente de cada tentativa; atomicidade/reconciliação entre atingimento, evento e envio após crash/concorrência.
- [ ] Alertas configuráveis de silêncio/recuperação com histerese e incidentes persistentes; UI de diagnóstico e métricas J16.
- [ ] Auditoria integral de multioperador por API, WebSocket, relatórios, notificações e comandos.
- [ ] Histórico/correlação completos de comandos e prevenção de duplicação operacional.
- [ ] Modelo financeiro, pagamentos e carência de dez dias (o comportamento financeiro ainda não foi alterado).
- [ ] Dashboard operacional, relatórios/auditoria ampliados e operações em lote auditáveis.
- [ ] Atualização do app em seu pacote posterior; preservar o contrato existente durante a homologação.
- [x] Integração local dos forks em branches próprias e ponteiro web atualizado;
- [ ] Push das branches para os forks; publicação e rollback ensaiados pelo usuário, que assumiu a atualização do servidor.

A produção não foi alterada. A atualização local e as correções iniciais não representam conclusão de todos os ciclos. Próxima etapa: homologação da base atual e fechamento da confiabilidade transacional das notificações, antes dos ciclos de gestão e operação.

## Referências oficiais

- Release 6.17: https://www.traccar.org/blog/traccar-6-17/
- Código da tag: https://github.com/traccar/traccar/tree/v6.17.0
- Build da tag: https://github.com/traccar/traccar/blob/v6.17.0/build.gradle
- Submódulo web correspondente: https://github.com/traccar/traccar-web/tree/09c926c245bb3e30d0f70f9541da4637e15496ee
