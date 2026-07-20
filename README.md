# [Traccar](https://www.traccar.org)

## Overview

Traccar is an open source GPS tracking system. This repository contains Java-based back-end service. It supports more than 200 GPS protocols and more than 2000 models of GPS tracking devices. Traccar can be used with any major SQL database system. It also provides easy to use [REST API](https://www.traccar.org/traccar-api/).

Other parts of Traccar solution include:

- [Traccar web app](https://github.com/traccar/traccar-web)
- [Traccar Manager app](https://github.com/traccar/traccar-manager)

There is also a set of mobile apps that you can use for tracking mobile devices:

- [Traccar Client app](https://github.com/traccar/traccar-client)

## Features

Some of the available features include:

- Real-time GPS tracking
- Driver behaviour monitoring
- Detailed and summary reports
- Geofencing functionality
- Alarms and notifications
- Account and device management
- Email and SMS support

## Build

Please read [build from source documentation](https://www.traccar.org/build/) on the official website.

## Deploy (fork gps-mobgotech)

Esta é a nossa versão customizada do Traccar (fork [gps-mobgotech](https://github.com/alderjanio-oliveira/gps-mobgotech), remote `mobgo`), com features próprias além do Traccar padrão (evento de bateria desconectada via `charger`, "distance reminders" — notificação de manutenção por km futuro com confirmação manual). Roda em produção num droplet DigitalOcean (`gps.mobgotech.com`), instalação padrão em `/opt/traccar`, banco MySQL, serviço systemd `traccar`.

Todo o deploy é feito pelo [`scripts/deploy.sh`](scripts/deploy.sh), rodado **de dentro do repositório clonado no próprio droplet**.

### Pré-requisitos no droplet

- Instalação Traccar já existente em `/opt/traccar` (`conf/traccar.xml`, `schema/`, `templates/`, `web/`, `tracker-server.jar`).
- Um runtime Java 21+ pra rodar o jar — o script detecta sozinho qual é (JRE embutido em `/opt/traccar/jre` se existir, senão o `ExecStart` da unit systemd do serviço, senão o `java` do PATH) e aborta se a versão for menor que 21. Pra forçar um binário específico, exporte `RUNTIME_JAVA_BIN=/caminho/pro/java`.
- Um JDK 21+ separado só para compilar (`javac`/gradle) — `sudo apt install openjdk-21-jdk-headless` se faltar. Pode apontar pra um JDK específico com a env var `BUILD_JAVA_HOME`.
- Node.js 20+ e `npm`, só necessário pras flags `--web`/`--web-rollback` (compilação do `traccar-web`).
- `mysql`, `mysqldump`, `curl`, `rsync` disponíveis no PATH.

### Backend: `--stg`, `--stg-stop`, `--prod`, `--rollback`, `--fix-changelog`

Não existe ambiente de staging separado — o próprio script simula um no mesmo droplet, clonando o banco de produção pra um banco descartável.

0. **`./scripts/deploy.sh --fix-changelog`** — fix pontual e idempotente pra uma inconsistência já existente na produção: a coluna `motionlatitude`/tabela `tc_device_device` do changelog `6.13.0` já foi aplicada fisicamente no banco em algum momento fora do fluxo padrão do Liquibase, mas o `DATABASECHANGELOG` não tem registro disso — sem esse fix, qualquer boot com jar novo quebra com `Duplicate column name 'motionlatitude'`. É aplicado **automaticamente** dentro do clone do `--stg`; rode esse comando direto uma vez contra a produção antes do primeiro `--prod`, senão ele vai travar na mesma coisa. Seguro rodar mais de uma vez (pede confirmação por escrever direto no banco real).
1. **`./scripts/deploy.sh --stg`** — compila o jar, clona o banco `traccar` real pra um banco `traccar_stg` (mesmo MySQL, banco separado), aplica o `--fix-changelog` na cópia automaticamente, sobe o jar novo na porta `8083` usando o mesmo runtime java de produção. Faz um healthcheck automático e, se passar, **deixa o processo rodando** pra validação manual — não mata sozinho. Se falhar, limpa sozinho o banco clonado e a pasta de staging (salvando o log de boot em `backups/stg-boot-<timestamp>.log` antes de limpar), sem deixar lixo acumulando em disco a cada tentativa.

   **Por padrão o clone é só schema + histórico do Liquibase (`DATABASECHANGELOG`/`DATABASECHANGELOGLOCK`), sem os dados de verdade** (`tc_positions`, `tc_users` etc ficam vazios). Isso já é suficiente pra validar que a migration nova aplica limpo contra a estrutura e o histórico reais, e evita dois problemas descobertos na prática: 1) precisar de ~1.5x o tamanho do banco em disco livre pra cada tentativa, e 2) inflar o binlog do MySQL na mesma proporção a cada clone (o binlog registra os INSERTs da importação também, e isso já causou um disco cheio numa sessão de testes). Pra clonar com dados completos (ex: quer clicar na tela com dados reais), rode `STG_FULL_DATA=true ./scripts/deploy.sh --stg` — nesse modo os **notificadores ficam desligados** (evita mandar email/Telegram real durante o teste, já que o clone teria dados reais de usuários) e o requisito de disco volta a ser ~1.5x o tamanho do banco.
2. **Validar manualmente** (opcional): de dentro do droplet, `curl http://localhost:8083/api/server`. Do seu computador, sem abrir porta nenhuma no firewall:
   ```
   ssh -L 8083:localhost:8083 usuario@gps.mobgotech.com
   ```
   depois acesse `http://localhost:8083` no navegador. Sem `STG_FULL_DATA=true` não tem usuário pra logar (só valida boot + migration); com `STG_FULL_DATA=true`, login com usuário/senha reais de produção.
3. **`./scripts/deploy.sh --stg-stop`** — mata o processo de staging e derruba o banco `traccar_stg`. Rodar `--stg` de novo sem isso primeiro é bloqueado pelo script (evita dois processos na mesma porta).
4. **`./scripts/deploy.sh --prod`** — só depois de confirmar que o `--stg` passou limpo. Pede confirmação explícita (digitar `CONFIRMAR`), então:
   - Faz backup do banco de produção (`mysqldump | gzip`, valida que não ficou vazio antes de prosseguir) e backup do release atual (jar, `lib/`, `schema/`, `templates/`) em `/opt/traccar/backups/`.
   - Para o serviço (`systemctl stop traccar`), troca **apenas** `tracker-server.jar`, `lib/`, `schema/*.xml` e `templates/**/*.vm`. Nunca toca em `conf/traccar.xml`, `jre/`, `web/` ou em tabelas já existentes do banco (as migrations deste fork só criam tabelas novas).
   - Sobe o serviço de novo e faz healthcheck em `/api/server`. Se falhar, **reverte sozinho** pro jar/schema/templates anteriores e avisa — o banco não precisa de rollback porque a migration é só aditiva.
5. **`./scripts/deploy.sh --rollback [TIMESTAMP]`** — reversão manual independente, útil se um problema aparecer horas/dias depois do deploy. Sem `TIMESTAMP`, usa o backup mais recente em `/opt/traccar/backups/release_*`.

### Frontend: `--web`, `--web-rollback`

O front (`traccar-web`) é servido como arquivos estáticos direto de `/opt/traccar/web/` pelo Jetty (lidos do disco a cada request) — **não precisa reiniciar o serviço** pra uma troca de front valer.

1. **`./scripts/deploy.sh --web`** — roda `npm ci && npm run build` dentro de `traccar-web/`, faz backup do `web/` atual em `/opt/traccar/backups/web_<timestamp>/`, e sincroniza (`rsync -a --delete`) o build novo (`traccar-web/dist/`) pra `/opt/traccar/web/`. Nenhum serviço é parado ou reiniciado. Depois de rodar, peça pra quem for validar dar um hard-refresh (Ctrl+Shift+R) — o navegador pode segurar a versão antiga em cache.
2. **`./scripts/deploy.sh --web-rollback [TIMESTAMP]`** — restaura `web/` a partir de um backup anterior (pede confirmação). Sem `TIMESTAMP`, usa o mais recente.

### Variáveis de ambiente (todas opcionais, com default sensato)

| Variável | Default | Uso |
| --- | --- | --- |
| `TRACCAR_HOME` | `/opt/traccar` | onde a instalação real vive |
| `SERVICE_NAME` | `traccar` | nome do serviço systemd |
| `STG_HOME` | `/opt/traccar-stg` | pasta temporária da staging |
| `STG_DB_NAME` | `traccar_stg` | banco temporário da staging |
| `STG_PORT` | `8083` | porta da staging |
| `STG_FULL_DATA` | `false` | `true` clona o banco com dados completos em vez de só schema+histórico do Liquibase |
| `HEALTH_TIMEOUT` | `90` (segundos) | tempo máximo esperando o healthcheck |
| `BUILD_JAVA_HOME` | (herda do ambiente) | JDK usado só pra compilar, se diferente do `JAVA_HOME` padrão |
| `RUNTIME_JAVA_BIN` | (auto-detectado) | força qual `java` roda o jar, se a detecção automática (JRE embutido → `ExecStart` do systemd → `java` do PATH) não achar o certo |

### Segurança

- `conf/traccar.xml` tem credenciais reais (banco, SMTP, bot do Telegram) — nunca commitar esse arquivo nem colar seu conteúdo em lugar nenhum fora do droplet.
- O script nunca imprime senha nenhuma no log; usa `MYSQL_PWD` só durante o comando específico e limpa a variável logo depois.
- Toda ação que mexe em produção (`--prod`, `--rollback`, `--web-rollback`) exige digitar `CONFIRMAR` antes de prosseguir.

## Team

- Anton Tananaev ([anton@traccar.org](mailto:anton@traccar.org))
- Andrey Kunitsyn ([andrey@traccar.org](mailto:andrey@traccar.org))

## License

    Apache License, Version 2.0

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
