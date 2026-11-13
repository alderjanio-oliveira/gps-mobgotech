# Guia prático dos novos recursos Mobgo

Este guia explica como usar as melhorias do Traccar integrado ao Rota-Tech, sem precisar conhecer o código.

**Situação atual:** os recursos estão implementados e testados localmente. Eles aparecerão no sistema publicado depois da atualização do backend, da web e da aplicação das migrations do banco. A validação no ambiente real ainda precisa ser feita. O aplicativo Rota-Tech não foi alterado nesta etapa.

## 1. Onde encontrar

Abra **Configurações** na web do rastreamento.

| Menu | Para que vamos usar |
| --- | --- |
| **Notificações** | Escolher quais acontecimentos geram avisos e por quais canais. |
| **Envios de notificações** | Conferir tentativas de envio e possíveis falhas por veículo. |
| **Operação** | Acompanhar comunicação, GPS e incidentes dos veículos. |
| **Histórico de comandos** | Consultar solicitações, encaminhamentos e respostas do rastreador. |
| **Mensalidades** | Configurar vencimentos, carência e registrar pagamentos dos clientes. |
| **Dispositivos** | Configurar os alertas opcionais de monitoramento em cada veículo. |

As opções e os veículos disponíveis dependem das permissões da conta.

## 2. Avisos de quilometragem do Rota-Tech

### Como funciona

Continuamos usando os lembretes que o aplicativo já salva. Não é necessário cadastrar novamente as metas existentes.

A meta mede a distância percorrida a partir de uma referência:

> Odômetro de referência: **15.000 km**. Intervalo desejado: **1.000 km**. A meta é atingida quando o acumulado chega a **16.000 km**.

São necessárias duas configurações:

1. **O lembrete no Rota-Tech:** define o veículo, a referência e a distância desejada.
2. **A notificação no servidor:** define quem recebe o aviso e por qual canal.

As preferências locais do aplicativo não configuram automaticamente e-mail ou outros canais no servidor.

### Como preparar o aviso

1. Confira no Rota-Tech se o lembrete está salvo e associado ao veículo correto.
2. Na web, abra **Configurações → Notificações** e adicione uma notificação.
3. No tipo, escolha **Lembrete de distância**.
4. Escolha os canais disponíveis que deseja usar, como web ou e-mail.
5. Salve e confira as associações de usuário e dispositivo em **Conexões**. Se usar **Todos os dispositivos**, a regra vale para os dispositivos aos quais o destinatário tem acesso.
6. Para e-mail, confirme que o destinatário tem endereço cadastrado e que o SMTP está configurado no servidor. Para outros canais, confira a configuração correspondente.
7. Valide o fluxo com uma conta e um veículo de teste antes de ampliar o uso.

O botão de teste dos canais faz um envio real. Ele verifica o canal; não substitui a verificação do vínculo entre lembrete, veículo, usuário e notificação.

### O que significa o estado do lembrete

| Estado | Significado |
| --- | --- |
| `pending` | Aguardando atingir a meta. |
| `notified` | O atingimento foi registrado; consulte os envios para conferir o resultado do canal. |
| `done` | Lembrete confirmado como concluído. |
| `cancelled` | Lembrete cancelado. |

**Atingir a meta não é comprovante de recebimento do aviso.** Ao concluir um ciclo, cadastre um novo lembrete para o próximo intervalo. Não altere o odômetro do veículo para reiniciar a meta.

### Se a meta foi atingida, mas o aviso não chegou

1. Confira se existe a notificação **Lembrete de distância**, com canal e associações corretos.
2. Abra **Envios de notificações**, selecione o veículo e clique em atualizar.
3. Se houver falha, consulte as tentativas e o erro exibido.
4. Se não houver nenhum envio, confira primeiro as regras e os vínculos; uma lista vazia não comprova que a meta não foi atingida.
5. Se o lembrete já estava marcado como `notified` antes da atualização, peça a análise do diagnóstico. A atualização não promete reenviar automaticamente alertas antigos que ficaram sem evento ou entrega.

Para suporte técnico, existe o diagnóstico protegido `/api/devices/ID_DO_VEICULO/distancereminders/ID_DO_LEMBRETE/diagnostics`. Ele ajuda a identificar ausência de regra, canal ou evento salvo. Não é necessário usar essa API na rotina normal.

## 3. Acompanhar os envios

Em **Configurações → Envios de notificações**:

1. Selecione o veículo.
2. Clique em atualizar.
3. Confira canal, estado, quantidade de tentativas e próximo horário de tentativa.
4. Clique no número de tentativas para abrir o histórico.

A tela mostra **seus últimos 100 envios** do veículo selecionado. Ela não reúne automaticamente os envios de todos os clientes do operador.

| Estado | Como interpretar |
| --- | --- |
| **Pendente** | Aguardando a tentativa ou uma nova tentativa. |
| **Enviando** | Uma tentativa está em andamento. |
| **Aceito pelo canal** | O adaptador concluiu a solicitação; não comprova entrega final nem leitura. |
| **Falhou** | O envio não foi concluído com sucesso. Veja o histórico e o erro. |
| **Cancelado** | O envio deixou de ser permitido ou aplicável, por exemplo após uma mudança de acesso. |
| **Incerto — verificar manualmente** | Não há confirmação suficiente do resultado. Verifique antes de tomar outra ação. |

O servidor guarda a fila no banco, retoma pendências após reiniciar e limita as tentativas por envio a cinco. Não precisa manter o celular aberto para o servidor processar a fila. Em falhas de comunicação com o provedor, uma mensagem pode ter sido entregue sem que a confirmação tenha chegado; por isso ainda pode ocorrer repetição.

## 4. Painel de operação e alertas de monitoramento

### Consultar a situação dos veículos

Abra **Configurações → Operação**. Use a busca e a paginação para localizar um veículo e abra **Detalhes** para consultar os incidentes.

A tela separa informações que podem ser diferentes:

- **Comunicação:** quando o servidor recebeu contato do rastreador.
- **Posição GPS:** validade e idade da localização.
- **Última ignição conhecida:** último valor informado, que pode estar desatualizado.
- **Último estado de relé reportado:** informação recebida do equipamento, acompanhada de seu contexto temporal.

Um rastreador pode se comunicar e continuar sem uma posição GPS recente. Silêncio não comprova falta de energia, hibernação ou defeito. Os totais exibidos no painel correspondem aos dispositivos da página atual.

### Habilitar alertas em um veículo

Os novos alertas são **opcionais**. Para ativá-los:

1. Abra **Configurações → Dispositivos** e edite o veículo, ou use **Configurar alertas** no painel de Operação.
2. Na seção de atributos, adicione **Alertas de monitoramento habilitados** e marque como verdadeiro.
3. Defina os limites desejados na tabela abaixo e salve.
4. Em **Notificações**, crie os avisos dos tipos desejados e associe canais, destinatários e dispositivos, como nos avisos de quilometragem.

| Atributo na interface | Comportamento padrão quando o monitoramento é habilitado |
| --- | --- |
| Limite sem comunicação | 15 minutos. Valor `0` desabilita esse tipo. |
| Limite de GPS | 30 minutos. Valor `0` desabilita esse tipo. |
| Limite offline com ignição | Desabilitado (`0`). Ao definir um limite, considera a última ignição conhecida como ligada. |
| Enviar alertas de recuperação | Habilitado. |
| Estabilidade para recuperação | 60 segundos antes de registrar a recuperação estável. |

Os tipos de notificação disponíveis são **Sem comunicação prolongada**, **Comunicação restabelecida**, **GPS indisponível**, **GPS restabelecido**, **Offline com última ignição ligada** e **Recuperação de offline com ignição**.

**Exemplo inicial:** habilite em um veículo de teste, use 15 minutos sem comunicação, 30 minutos para GPS e 60 segundos de estabilidade. Mantenha o alerta de ignição desabilitado até conferir se o equipamento informa esse atributo corretamente.

A verificação ocorre periodicamente, aproximadamente a cada 30 segundos. Os alertas não são instantâneos no segundo exato do limite. Sem uma referência conhecida de comunicação, o sistema não inventa um incidente de silêncio. A recuperação estável evita abrir e fechar o mesmo incidente a cada oscilação breve.

## 5. Histórico de comandos e bloqueio

Continue enviando comandos pelo fluxo normal do veículo. Depois, abra **Configurações → Histórico de comandos**, selecione o veículo e atualize a lista.

A tela mostra as últimas 100 solicitações e as últimas 100 respostas do dispositivo.

| Estado da solicitação | Significado |
| --- | --- |
| **Solicitado** | O pedido foi registrado. |
| **Na fila** | O comando está aguardando encaminhamento. |
| **Encaminhado ao transporte** | O servidor repassou o comando ao transporte disponível. |
| **Liberado da fila** | O comando saiu da fila para encaminhamento. |
| **Incerto** | Não foi possível comprovar o resultado do processamento. |

As respostas recebidas do rastreador aparecem em uma área separada. Sem um identificador confirmado pelo protocolo, uma resposta não é atribuída automaticamente a uma solicitação específica.

**Para conferir um bloqueio:** consulte a solicitação, a resposta e o último estado de relé reportado, incluindo seus horários. Um “OK” ou um comando encaminhado não comprova corte físico do motor. Mesmo uma informação de relé não demonstra, sozinha, que aquela solicitação causou a mudança.

A web evita duplo clique e usa uma identificação da solicitação para reduzir duplicações. Há também proteção contra repetição imediata de comandos de relé iguais. Se o resultado ficar incerto, consulte o histórico e a comunicação do equipamento antes de repetir manualmente.

## 6. Mensalidades e dez dias de carência

### Quem configura

O administrador ou operador autorizado configura a mensalidade de outro usuário dentro do seu escopo. O cliente consulta a própria situação e o histórico, mas não altera vencimento nem confirma o próprio pagamento. Uma conta somente de leitura não pode fazer essas alterações.

### Cadastrar o financeiro do cliente

1. Abra **Configurações → Mensalidades**.
2. Selecione o cliente.
3. Informe o **Vencimento**.
4. Mantenha **Carência (dias)** em `10`, ou ajuste conforme o contrato.
5. Confira o **Fuso do vencimento**; o padrão é `America/Manaus`.
6. Clique em salvar.

Esse cadastro é opcional e individual. A atualização não ativa cobrança nem muda o vencimento de todos os clientes automaticamente. Contas de administrador global não são elegíveis para esse cadastro financeiro.

### Como a carência é contada

Exemplo com vencimento em **10/11/2026**, carência de **10 dias** e fuso **America/Manaus**:

| Período | Situação |
| --- | --- |
| Até o fim de 10/11 | **Em dia**. |
| De 11/11 até o fim de 20/11 | **Em carência**, com acesso permitido. |
| A partir de 00h de 21/11, no fuso configurado | **Acesso suspenso**. |

Durante a carência, a página principal da web exibe um aviso. Após a suspensão financeira, o acesso do usuário é recusado; conexões abertas são revisadas aproximadamente a cada 30 segundos. **A coleta das posições dos rastreadores continua funcionando.** Suspensão financeira não envia comando de bloqueio ao veículo.

### Cliente com expiração antiga

Na criação do cadastro financeiro, existe a opção **Substituir a expiração antiga por este vencimento financeiro**.

- Marque somente quando quiser que a nova regra financeira substitua a expiração antiga da conta.
- Sem essa opção, uma expiração antiga ainda pode impedir o acesso, mesmo durante a carência financeira.
- Essa substituição é oferecida na criação do cadastro, não como um botão de renovação mensal.
- Uma conta desabilitada manualmente continua desabilitada.

### Registrar um pagamento

1. Selecione o cliente em **Mensalidades**.
2. Na área **Confirmar pagamento**, informe o valor em reais.
3. Escolha o próximo vencimento: ele precisa avançar em relação ao anterior e não pode estar no passado.
4. Clique em **Confirmar pagamento**.
5. Confira o novo vencimento e o lançamento no **Histórico de pagamentos**.

Use ponto decimal se o campo exigir; por exemplo, `59.90` representa R$ 59,90.

O registro é manual. Ele não consulta o banco, não confirma Pix e não cobra o cliente automaticamente. O próximo vencimento é informado por quem registra o pagamento.

Ao regularizar o vencimento, a restrição financeira é removida. Outras restrições da conta, como desabilitação manual ou expiração antiga mantida, continuam valendo. Se a sessão tiver sido encerrada, o cliente precisa entrar novamente.

## 7. Permissões de operadores e clientes

Continuamos usando os vínculos nativos de usuários, grupos e dispositivos.

- **Administrador global:** administra o sistema inteiro. Reserve esse perfil a quem realmente precisa desse alcance.
- **Operador/gerente:** administra os usuários e recursos vinculados ao seu escopo; não ganha acesso global por ser operador.
- **Cliente:** acessa os recursos compartilhados com sua conta.

Ao retirar o acesso de um cliente a um dispositivo, confira também se ele recebe acesso por algum grupo. Remover um vínculo direto não remove um acesso legítimo que ainda existe pelo grupo.

As melhorias recalculam o acesso das conexões abertas após mudanças de vínculos e grupos. Não é necessário esperar o cliente sair e entrar para parar de transmitir novas posições de um veículo ao qual ele perdeu acesso. Isso não apaga informações que já foram recebidas ou exportadas anteriormente.

## 8. Rotina sugerida para a equipe

**Na ativação de um cliente:** confira usuário, dispositivos e grupos; configure os avisos desejados; cadastre a mensalidade somente se essa conta for usar o novo financeiro.

**No acompanhamento diário:** consulte Operação, investigue incidentes e confira os envios com falha. Interprete sempre a idade da informação antes de tratar o último estado como atual.

**Após um comando:** confira o histórico e a telemetria disponível. Verifique resultados incertos antes de repetir a ação.

**Após um pagamento:** registre valor e próximo vencimento; confira a situação financeira e o histórico.

## 9. O que ainda fica para as próximas etapas

Este guia cobre os recursos integrados nesta atualização. Métricas operacionais ampliadas, operações em lote e ajustes adicionais de mapa continuam no plano. A evolução das telas e preferências do aplicativo Rota-Tech será feita em uma etapa própria.

Antes do uso em produção, validar a atualização com uma cópia restaurada do MySQL, os canais reais de notificação e um rastreador de teste. A publicação no servidor continua sob sua responsabilidade, usando os scripts de deploy existentes.

Para a equipe técnica: [acompanhamento da execução](../plans/2026-11-13-execucao.md) e [procedimento de homologação](../operations/rota-tech-6.17-homologacao.md).

Para preparar o teste em produção, leia a [revisão do backup e rollback](../operations/2026-11-13-revisao-backup-rollback.md). O script atual salva o banco, mas seu rollback restaura apenas os arquivos da aplicação.
