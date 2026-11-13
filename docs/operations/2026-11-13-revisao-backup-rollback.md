# Revisão do backup e rollback do deploy

Data: 13/11/2026.
Script revisado: `scripts/deploy.sh`, no repositório `/home/alderjanio/personal-projects/traccar`.

## Resultado para decidir sobre o teste em produção

**O script continua criando backups, mas o retorno completo após a atualização 6.17 não está garantido. Não tratar o rollback atual como uma forma segura de desfazer todas as alterações desta atualização.**

A revisão foi feita por leitura do código, validação de sintaxe Bash e oito simulações locais. Foram usados arquivos temporários e substitutos para MySQL, systemd e teste HTTP. Nenhuma conexão, cópia, backup ou restauração foi executada na produção. O script não foi alterado nesta revisão.

## O que ele salva hoje

| Comando | Backup | Retorno disponível |
| --- | --- | --- |
| `--prod` | Dump MySQL compactado em `/opt/traccar/backups/traccar_TIMESTAMP.sql.gz`; JAR, lib, schema e templates em `release_TIMESTAMP/`. | O retorno automático e `--rollback` restauram somente JAR, lib, schema e templates. |
| `--web` | Conteúdo de web em `/opt/traccar/backups/web_TIMESTAMP/`. | `--web-rollback TIMESTAMP` restaura essa cópia. |

Esses caminhos usam o padrão `TRACCAR_HOME=/opt/traccar`. Se a variável for alterada, os backups ficam dentro do diretório informado.

Configuração, runtime Java e mídia/dados externos não fazem parte do backup de release. O script não substitui `conf/traccar.xml` nem `jre/` durante o deploy. Os backups ficam no mesmo servidor; não são proteção contra perda do disco ou do servidor.

## Problemas confirmados

### 1. O retorno do backend não restaura o banco

Tanto o retorno automático quanto `--rollback` deixam o banco como está. Isso importa porque a migration 6.15 altera tipos de colunas e a 6.17 remove vínculos recíprocos de `tc_device_device`. As novas extensões também criam tabelas e registram migrations.

Voltar o JAR e os arquivos de schema não desfaz essas operações nem o histórico Liquibase. A compatibilidade da aplicação antiga com o banco modificado não foi comprovada.

**Correção necessária:** definir e ensaiar o retorno conjunto de aplicação e banco. Restaurar um backup do banco descarta alterações posteriores àquele backup; precisa preservar o estado atual antes de restaurar e explicitar a perda/reconciliação dos dados do intervalo.

### 2. Nem toda falha aciona o rollback automático

A recuperação está apenas no ramo de falha do teste HTTP. Com `set -e`, uma falha anterior em `cp`, `rsync` ou `systemctl start` pode encerrar o script antes de chegar a esse ramo.

Nas simulações, uma falha de cópia deixou o serviço parado. Uma falha no comando de inicialização encerrou a execução sem restaurar os arquivos anteriores.

**Correção necessária:** tratar falhas por etapa, incluindo cópia e inicialização. Antes de subir a aplicação antiga, decidir sobre a compatibilidade/restauração do banco; não anunciar retorno completo após restaurar apenas arquivos.

### 3. O backup do banco antecede a parada da aplicação

O serviço continua funcionando durante o dump e só é parado depois do backup dos arquivos. Assim, pode haver dados gravados após o ponto coberto pelo backup.

O script usa `pipefail`, o que corretamente interrompe a publicação se `mysqldump` falhar. Porém, pode deixar um arquivo compactado parcial com aparência de backup. Verificar apenas se o arquivo existe e tem tamanho não comprova que ele pode ser restaurado.

**Correção necessária:** publicar o nome final somente depois de concluir/verificar o backup, registrar seu estado e testar a restauração em banco separado. Definir o ponto de corte das gravações para a recuperação. Não prometer que nenhuma posição ou alteração será perdida ao voltar o banco.

### 4. A leitura da configuração só aceita aspas simples

A função `conf_value` usa um padrão que reconhece `key='web.port'`, mas não `key="web.port"`, embora ambos sejam XML válido. A simulação com aspas duplas falhou.

**Correção necessária:** ler XML de maneira compatível com os dois formatos, inclusive valores escapados e a porta do banco. Não foi lida a configuração real do servidor para confirmar qual formato ele usa.

### 5. Backend e web têm backups e retornos separados

Atualizar o backend não salva nem atualiza a web. Se os dois forem publicados e for necessário voltar, é preciso selecionar os backups correspondentes de cada um, além da estratégia do banco. Não assumir que o último backup de web e o último de backend pertencem ao mesmo conjunto.

## Simulações executadas

| Cenário | Resultado observado |
| --- | --- |
| Publicação normal | Dump e cópia do release feitos antes da parada; arquivos novos publicados. |
| Erro no dump | Publicação interrompida antes de parar o serviço; pode sobrar arquivo compactado parcial. |
| Erro ao copiar o JAR novo | Script interrompido; serviço permaneceu parado; sem recuperação automática. |
| Erro no comando de iniciar serviço | Script interrompido; arquivos novos permaneceram; sem recuperação automática. |
| Falha no teste HTTP | Arquivos antigos restaurados; banco simulado permaneceu migrado. |
| `--rollback` manual | Arquivos antigos restaurados; banco simulado permaneceu migrado. |
| `--web-rollback` | Conteúdo anterior da web restaurado, sem reinício de serviço. |
| Configuração XML com aspas duplas | Leitura da porta falhou. |

A sintaxe Bash passou. As simulações verificam o fluxo do script, não os privilégios MySQL, espaço real, integridade de um dump real, tempo de restauração nem o funcionamento da aplicação antiga sobre dados restaurados.

## Antes de executar o teste em produção

1. Corrigir os pontos de tratamento de falha e leitura da configuração.
2. Definir um conjunto de recuperação: banco, backend e web correspondentes; preservar também configuração e identificação do runtime atual.
3. Restaurar o dump em banco independente e conferir que a versão anterior inicia com ele.
4. Registrar o intervalo de indisponibilidade e o que acontecerá com posições, comandos e pagamentos posteriores ao backup.
5. Durante a publicação, conferir também a versão realmente iniciada, ingestão GPS, login e notificações. Uma resposta HTTP em `/api/server` sozinha não valida esses fluxos.

Não executar uma restauração destrutiva do banco automaticamente sem definir esses passos. A publicação e o retorno reais continuam a cargo do operador.

Referência para consistência, permissões e limites do dump: [manual oficial do MySQL — mysqldump](https://dev.mysql.com/doc/refman/8.0/en/mysqldump.html).
