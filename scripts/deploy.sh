#!/usr/bin/env bash
#
# Deploy script for the go-nansen Traccar fork.
#
# Run from inside the cloned repo on the droplet:
#   ./scripts/deploy.sh --stg              dry-run against a throwaway copy of the prod DB, no prod service touched
#   ./scripts/deploy.sh --stg-stop         tear down the --stg process/database
#   ./scripts/deploy.sh --prod             real upgrade of the running /opt/traccar install (backend)
#   ./scripts/deploy.sh --rollback [TS]    restore jar/lib/schema/templates from a prior --prod backup
#   ./scripts/deploy.sh --web              build traccar-web and sync it into /opt/traccar/web, no restart
#   ./scripts/deploy.sh --web-rollback [TS] restore web/ from a prior --web backup
#
# --prod only ever touches tracker-server.jar, lib/, schema/*.xml and templates/**/*.vm.
# --web only ever touches web/. conf/traccar.xml, jre/ and the database's existing tables
# are never modified by this script (the migration this repo ships only ADDS new tables).

set -euo pipefail

TRACCAR_HOME="${TRACCAR_HOME:-/opt/traccar}"
SERVICE_NAME="${SERVICE_NAME:-traccar}"
STG_HOME="${STG_HOME:-/opt/traccar-stg}"
STG_DB_NAME="${STG_DB_NAME:-traccar_stg}"
STG_PORT="${STG_PORT:-8083}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-90}"

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONF_FILE="$TRACCAR_HOME/conf/traccar.xml"
BACKUP_ROOT="$TRACCAR_HOME/backups"

log()  { printf '\033[1;34m[deploy]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[deploy]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[deploy]\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
    cat <<EOF
Usage: $0 --stg | --stg-stop | --prod | --rollback [TIMESTAMP] | --web | --web-rollback [TIMESTAMP]

  --stg          Build the jar, clone the prod DB schema + Liquibase history (no table
                 data by default — set STG_FULL_DATA=true for a full data clone) into
                 a throwaway '$STG_DB_NAME' database, boot the new jar on port
                 $STG_PORT against that copy, health-check it, then leave it running
                 for manual validation. Never touches the running prod service or DB.
  --stg-stop     Kill the staging process and drop the '$STG_DB_NAME' database.
  --prod         Build the jar, back up the DB and the current release files,
                 stop $SERVICE_NAME, swap in the new jar/lib/schema/templates,
                 start $SERVICE_NAME, health-check it, auto-rollback on failure.
  --rollback     Restore jar/lib/schema/templates from a previous --prod backup.
                 Defaults to the most recent backup if TIMESTAMP is omitted.
  --web          Build traccar-web and sync its static output into $TRACCAR_HOME/web.
                 No service restart, no DB involved — backs up the current web/ first.
  --web-rollback Restore web/ from a previous --web backup (defaults to the latest).
  --fix-changelog  One-off fix for changelog-6.13.0 being applied physically but not
                 recorded in DATABASECHANGELOG (pre-existing prod drift). Idempotent.
                 Applied automatically to the clone inside --stg; run this once
                 directly against prod before --prod if it hasn't been fixed there yet.
EOF
}

# ---------- shared helpers ----------

conf_value() {
    grep -oP "(?<=key='$1'>)[^<]*" "$CONF_FILE" | head -n1
}

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "comando obrigatório não encontrado: $1"
}

# Descobre qual binário java o serviço realmente usa (JRE embutido em $TRACCAR_HOME/jre,
# ou o java do sistema, como no ExecStart=/usr/bin/java ... da unit systemd). Não assume
# o layout oficial do instalador — cada instalação pode ter feito diferente.
detect_runtime_java() {
    if [ -n "${RUNTIME_JAVA_BIN:-}" ]; then
        echo "$RUNTIME_JAVA_BIN"
        return
    fi
    if [ -x "$TRACCAR_HOME/jre/bin/java" ]; then
        echo "$TRACCAR_HOME/jre/bin/java"
        return
    fi
    local from_unit
    from_unit=$(systemctl cat "$SERVICE_NAME" 2>/dev/null | grep -oP '(?<=^ExecStart=)\S+' | head -n1)
    if [ -n "$from_unit" ] && [ -x "$from_unit" ]; then
        echo "$from_unit"
        return
    fi
    command -v java 2>/dev/null || true
}

preflight() {
    [ -f "$REPO_DIR/build.gradle" ] || die "rode este script de dentro do repositório clonado (build.gradle não encontrado)"
    [ -f "$CONF_FILE" ] || die "config de produção não encontrada em $CONF_FILE (TRACCAR_HOME=$TRACCAR_HOME está certo?)"

    RUNTIME_JAVA_BIN=$(detect_runtime_java)
    [ -n "$RUNTIME_JAVA_BIN" ] && [ -x "$RUNTIME_JAVA_BIN" ] \
        || die "não encontrei o java usado pelo serviço $SERVICE_NAME. Exporte RUNTIME_JAVA_BIN=/caminho/pro/java e rode de novo."
    log "runtime java detectado: $RUNTIME_JAVA_BIN"

    local runtime_version
    runtime_version=$("$RUNTIME_JAVA_BIN" -version 2>&1 | grep -oP '"\K[0-9]+' | head -n1)
    [ "$runtime_version" -ge 21 ] || die "$RUNTIME_JAVA_BIN é Java $runtime_version, o build exige 21+. Atualize o runtime antes de continuar (fora do escopo deste script)."

    require_cmd mysqldump
    require_cmd mysql
    require_cmd curl
    require_cmd rsync

    if ! (cd "$REPO_DIR" && [ -n "${BUILD_JAVA_HOME:-}" ] && export JAVA_HOME="$BUILD_JAVA_HOME"; ./gradlew --no-daemon -v) >/dev/null 2>&1; then
        die "não achei um JDK 21+ para compilar. Instale com 'sudo apt install openjdk-21-jdk-headless' e/ou exporte BUILD_JAVA_HOME."
    fi
}

parse_db_conf() {
    DB_URL=$(conf_value "database.url")
    DB_USER=$(conf_value "database.user")
    DB_PASS=$(conf_value "database.password")
    WEB_PORT=$(conf_value "web.port")
    [ -n "$DB_URL" ] && [ -n "$DB_USER" ] || die "não consegui ler database.url/database.user de $CONF_FILE"

    DB_HOST=$(echo "$DB_URL" | sed -E 's#jdbc:mysql://([^/:]+).*#\1#')
    DB_NAME=$(echo "$DB_URL" | sed -E 's#jdbc:mysql://[^/]+/([^?]+).*#\1#')
    [ -n "$DB_HOST" ] && [ -n "$DB_NAME" ] || die "não consegui extrair host/nome do banco de database.url"
}

db_size_bytes() {
    export MYSQL_PWD="$DB_PASS"
    local bytes
    bytes=$(mysql -h "$DB_HOST" -u "$DB_USER" -N -B -e \
        "SELECT COALESCE(SUM(data_length + index_length), 0) FROM information_schema.tables WHERE table_schema = '$DB_NAME';")
    unset MYSQL_PWD
    echo "${bytes:-0}"
}

avail_bytes() {
    df --output=avail -B1 "$TRACCAR_HOME" | tail -n1 | tr -d ' '
}

human_bytes() {
    awk -v b="$1" 'BEGIN { printf "%.1fG", b / 1024 / 1024 / 1024 }'
}

# Garante espaço em disco suficiente ANTES de qualquer operação que grave dados —
# um clone de banco cheio ou uma build sem margem podem encher o disco (já aconteceu:
# MySQL derruba o próprio processo com binlog_error_action=ABORT_SERVER quando o disco
# enche no meio de uma escrita).
require_disk_space() {
    local needed="$1" label="$2"
    local avail
    avail=$(avail_bytes)
    if [ "$avail" -lt "$needed" ]; then
        die "espaço em disco insuficiente para $label: disponível $(human_bytes "$avail"), necessário ~$(human_bytes "$needed"). Libere espaço antes de continuar."
    fi
    log "espaço em disco ok para $label: disponível $(human_bytes "$avail"), necessário ~$(human_bytes "$needed")"
}

build_jar() {
    log "compilando (pode levar alguns minutos)..."
    (
        cd "$REPO_DIR"
        [ -n "${BUILD_JAVA_HOME:-}" ] && export JAVA_HOME="$BUILD_JAVA_HOME"
        # --no-daemon + heap baixo: droplets pequenos sem swap derrubam (OOM) o daemon do
        # Gradle default. Sem daemon persistente, a JVM da build morre e libera a memória
        # assim que termina, em vez de ficar residente entre execuções.
        export GRADLE_OPTS="-Xmx512m -XX:MaxMetaspaceSize=256m"
        ./gradlew --no-daemon assemble -x test -x checkstyleMain -x checkstyleTest
    )
    [ -f "$REPO_DIR/target/tracker-server.jar" ] || die "build não gerou target/tracker-server.jar"
    log "build ok: $REPO_DIR/target/tracker-server.jar"
}

wait_for_health() {
    local url="$1" timeout="$2" waited=0
    log "aguardando $url responder (timeout ${timeout}s)..."
    while [ "$waited" -lt "$timeout" ]; do
        if curl -sf -o /dev/null "$url"; then
            log "respondeu OK após ${waited}s"
            return 0
        fi
        sleep 3
        waited=$((waited + 3))
    done
    return 1
}

# ---------- fix pontual: changelog-6.13.0 já aplicado fisicamente mas sem registro no
# DATABASECHANGELOG (herdado de uma migration manual anterior a este script). Sem isso,
# o Liquibase tenta rodar o changeset de novo e quebra em "Duplicate column name". Idempotente
# — seguro rodar mais de uma vez, e seguro mesmo se tc_device_device/FK já existirem.

fix_changelog_6130() {
    local target_db="$1"
    log "aplicando fix de compatibilidade do changelog-6.13.0 em $target_db..."
    export MYSQL_PWD="$DB_PASS"
    mysql -h "$DB_HOST" -u "$DB_USER" "$target_db" <<'SQL'
CREATE TABLE IF NOT EXISTS `tc_device_device` (
  `deviceid` INT NOT NULL,
  `linkeddeviceid` INT NOT NULL
);

SET @fk_exists = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
  WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'fk_device_device_deviceid');
SET @sql = IF(@fk_exists = 0,
  'ALTER TABLE `tc_device_device` ADD CONSTRAINT `fk_device_device_deviceid` FOREIGN KEY (`deviceid`) REFERENCES `tc_devices`(`id`) ON DELETE CASCADE',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO `DATABASECHANGELOG`
  (ID, AUTHOR, FILENAME, DATEEXECUTED, ORDEREXECUTED, EXECTYPE, MD5SUM, DESCRIPTION, LIQUIBASE, LABELS, CONTEXTS)
SELECT 'changelog-6.13.0', 'author', 'changelog-6.13.0', NOW(),
  (SELECT COALESCE(MAX(ORDEREXECUTED), 0) + 1 FROM (SELECT ORDEREXECUTED FROM DATABASECHANGELOG) t),
  'EXECUTED', NULL, 'addColumn, createTable tc_device_device, addForeignKeyConstraint', NULL, NULL, NULL
WHERE NOT EXISTS (
  SELECT 1 FROM `DATABASECHANGELOG` WHERE ID='changelog-6.13.0' AND AUTHOR='author' AND FILENAME='changelog-6.13.0'
);
SQL
    unset MYSQL_PWD
    log "fix aplicado em $target_db."
}

run_fix_changelog() {
    [ -f "$CONF_FILE" ] || die "config de produção não encontrada em $CONF_FILE (TRACCAR_HOME=$TRACCAR_HOME está certo?)"
    require_cmd mysql
    parse_db_conf
    warn "isso vai gravar direto no banco de produção '$DB_NAME' (fora do fluxo normal do --prod)."
    read -r -p "Digite CONFIRMAR para prosseguir: " confirm
    [ "$confirm" = "CONFIRMAR" ] || die "abortado pelo operador"
    fix_changelog_6130 "$DB_NAME"
}

# ---------- --stg ----------

run_stg() {
    if [ -f "$STG_HOME/pid" ] && kill -0 "$(cat "$STG_HOME/pid")" 2>/dev/null; then
        die "já tem um staging rodando (PID $(cat "$STG_HOME/pid")). Rode ./scripts/deploy.sh --stg-stop primeiro."
    fi

    preflight
    parse_db_conf

    local db_size required
    db_size=$(db_size_bytes)
    if [ "${STG_FULL_DATA:-false}" = "true" ]; then
        required=$((db_size * 3 / 2 + 500 * 1024 * 1024))
        require_disk_space "$required" "clonar $DB_NAME -> $STG_DB_NAME com dados completos (tamanho atual: $(human_bytes "$db_size"))"
    else
        required=$((300 * 1024 * 1024))
        require_disk_space "$required" "clonar $DB_NAME -> $STG_DB_NAME (só schema + histórico do Liquibase, sem dados)"
    fi

    build_jar

    export MYSQL_PWD="$DB_PASS"
    mysql -h "$DB_HOST" -u "$DB_USER" -e "DROP DATABASE IF EXISTS $STG_DB_NAME; CREATE DATABASE $STG_DB_NAME;"
    if [ "${STG_FULL_DATA:-false}" = "true" ]; then
        log "clonando $DB_NAME -> $STG_DB_NAME com dados completos (STG_FULL_DATA=true)..."
        mysqldump --no-tablespaces -h "$DB_HOST" -u "$DB_USER" "$DB_NAME" \
            | mysql -h "$DB_HOST" -u "$DB_USER" "$STG_DB_NAME"
    else
        log "clonando $DB_NAME -> $STG_DB_NAME (só schema + histórico do Liquibase — sem tc_positions/tc_events etc,"
        log "não infla o binlog. Pra clonar com dados completos: STG_FULL_DATA=true ./scripts/deploy.sh --stg)"
        {
            mysqldump --no-tablespaces --no-data -h "$DB_HOST" -u "$DB_USER" "$DB_NAME"
            # dados de verdade só das tabelas de controle: histórico do Liquibase +
            # tc_servers (config global, sempre 1 linha — sem ela cacheManager.getServer()
            # retorna null e QUALQUER request, incluindo o healthcheck, quebra com 500).
            mysqldump --no-tablespaces --no-create-info -h "$DB_HOST" -u "$DB_USER" "$DB_NAME" \
                DATABASECHANGELOG DATABASECHANGELOGLOCK tc_servers
        } | mysql -h "$DB_HOST" -u "$DB_USER" "$STG_DB_NAME"
    fi
    unset MYSQL_PWD

    fix_changelog_6130 "$STG_DB_NAME"

    log "montando ambiente de staging em $STG_HOME..."
    rm -rf "$STG_HOME"
    mkdir -p "$STG_HOME/conf" "$STG_HOME/logs" "$STG_HOME/data"
    cp "$REPO_DIR/target/tracker-server.jar" "$STG_HOME/"
    cp -r "$REPO_DIR/target/lib" "$STG_HOME/"
    cp -r "$REPO_DIR/schema" "$STG_HOME/"
    cp -r "$REPO_DIR/templates" "$STG_HOME/"
    # WebServer resolve web.path (default "./web") com toRealPath() e quebra com
    # NoSuchFileException se a pasta não existir — não precisamos testar o front aqui,
    # só apontar pra um web/ real já existente (o de produção serve bem).
    cp -r "$TRACCAR_HOME/web" "$STG_HOME/"

    # staging aponta pro banco clonado, porta separada, e SEM notificadores reais
    # (o banco clonado tem emails/telegram/chatId reais de usuários de produção)
    sed \
        -e "s#\(database.url'>jdbc:mysql://[^/]*/\)$DB_NAME#\1$STG_DB_NAME#" \
        -e "s#\(web.port'>\)[0-9]*#\1$STG_PORT#" \
        -e "s#\(notificator.types'>\)[^<]*#\1#" \
        "$CONF_FILE" > "$STG_HOME/conf/traccar.xml"

    log "subindo jar novo em staging (porta $STG_PORT)..."
    (cd "$STG_HOME" && nohup "$RUNTIME_JAVA_BIN" -jar tracker-server.jar conf/traccar.xml \
        > "$STG_HOME/boot.log" 2>&1 & echo $! > "$STG_HOME/pid")
    STG_PID=$(cat "$STG_HOME/pid")

    if wait_for_health "http://localhost:$STG_PORT/api/server" "$HEALTH_TIMEOUT"; then
        local login_hint
        if [ "${STG_FULL_DATA:-false}" = "true" ]; then
            login_hint="  - login: mesmo usuário/senha de produção (clone com dados completos)"
        else
            login_hint="  - sem usuários pra logar (clone só de schema, sem dados) — isso valida boot + migration,
    não a tela. Pra clonar com dados de verdade: STG_FULL_DATA=true ./scripts/deploy.sh --stg"
        fi
        cat <<EOF

STAGING OK — migration + boot com o jar novo funcionaram.
Log do Liquibase/console: $STG_HOME/boot.log
Log da aplicação:         $STG_HOME/logs/tracker-server.log

Fica rodando em background (PID $STG_PID) pra você validar manualmente:
  - de dentro do droplet:  curl http://localhost:$STG_PORT/api/server
  - do seu computador:     ssh -L $STG_PORT:localhost:$STG_PORT $(whoami)@<este-host>
                            depois abra http://localhost:$STG_PORT no navegador
$login_hint

Quando terminar de validar, encerre com:
  ./scripts/deploy.sh --stg-stop
EOF
    else
        local saved_dir
        saved_dir="$BACKUP_ROOT/stg-boot-$(date -u +%Y%m%dT%H%M%SZ)"
        mkdir -p "$saved_dir"
        cp "$STG_HOME/boot.log" "$saved_dir/" 2>/dev/null || true
        # boot.log só tem o que o Liquibase imprime direto no console — o log de verdade
        # da aplicação (WebServer subindo, erros de startup, etc) vai pro arquivo abaixo,
        # não pro stdout. Preservar os dois antes de limpar o staging.
        cp -r "$STG_HOME/logs" "$saved_dir/" 2>/dev/null || true
        warn "STAGING FALHOU — logs salvos em $saved_dir (boot.log + logs/)"
        cleanup_stg
        die "staging não respondeu dentro de ${HEALTH_TIMEOUT}s (banco $STG_DB_NAME e $STG_HOME já foram limpos, sem deixar lixo em disco)"
    fi
}

cleanup_stg() {
    if [ -f "$STG_HOME/pid" ]; then
        local pid
        pid=$(cat "$STG_HOME/pid")
        kill "$pid" 2>/dev/null || true
        log "processo de staging (PID $pid) encerrado."
    fi

    if [ -f "$CONF_FILE" ]; then
        local drop_user drop_host
        drop_user=$(conf_value "database.user")
        drop_host=$(conf_value "database.url" | sed -E 's#jdbc:mysql://([^/:]+).*#\1#')
        export MYSQL_PWD="$(conf_value "database.password")"
        if ! mysql -h "$drop_host" -u "$drop_user" -e "DROP DATABASE IF EXISTS $STG_DB_NAME;"; then
            warn "não consegui derrubar o banco $STG_DB_NAME, remova manualmente depois"
        fi
        unset MYSQL_PWD
    fi

    rm -rf "$STG_HOME"
}

run_stg_stop() {
    if [ ! -f "$STG_HOME/pid" ]; then
        warn "nenhum pid de staging encontrado em $STG_HOME/pid"
    fi
    cleanup_stg
    log "$STG_HOME e o banco $STG_DB_NAME removidos."
}

# ---------- --prod ----------

backup_release() {
    local ts="$1"
    local dir="$BACKUP_ROOT/release_$ts"
    mkdir -p "$dir"
    cp "$TRACCAR_HOME/tracker-server.jar" "$dir/"
    cp -r "$TRACCAR_HOME/lib" "$dir/"
    cp -r "$TRACCAR_HOME/schema" "$dir/"
    cp -r "$TRACCAR_HOME/templates" "$dir/"
    echo "$dir"
}

restore_release() {
    local dir="$1"
    [ -d "$dir" ] || die "backup não encontrado: $dir"
    log "restaurando release de $dir..."
    cp "$dir/tracker-server.jar" "$TRACCAR_HOME/"
    rsync -a --delete "$dir/lib/" "$TRACCAR_HOME/lib/"
    rsync -a --delete "$dir/schema/" "$TRACCAR_HOME/schema/"
    rsync -a --delete "$dir/templates/" "$TRACCAR_HOME/templates/"
}

run_prod() {
    preflight
    parse_db_conf

    local db_size required
    db_size=$(db_size_bytes)
    required=$((db_size / 2 + 1024 * 1024 * 1024))
    require_disk_space "$required" "backup do banco $DB_NAME + release novo (tamanho atual do banco: $(human_bytes "$db_size"))"

    build_jar

    cat <<EOF

Isso vai:
  1. Fazer backup do banco '$DB_NAME' e do release atual em $BACKUP_ROOT
  2. Parar o serviço '$SERVICE_NAME'
  3. Trocar tracker-server.jar, lib/, schema/*.xml e templates/**/*.vm
  4. Subir o serviço de novo e validar que respondeu
  5. Se falhar, reverter sozinho pro jar/schema/templates anteriores

conf/traccar.xml, jre/, web/ e as tabelas já existentes do banco NÃO são tocados.

EOF
    read -r -p "Digite CONFIRMAR para prosseguir: " confirm
    [ "$confirm" = "CONFIRMAR" ] || die "abortado pelo operador"

    local ts
    ts=$(date -u +%Y%m%dT%H%M%SZ)
    mkdir -p "$BACKUP_ROOT"

    log "backup do banco $DB_NAME..."
    export MYSQL_PWD="$DB_PASS"
    mysqldump --no-tablespaces -h "$DB_HOST" -u "$DB_USER" "$DB_NAME" | gzip > "$BACKUP_ROOT/traccar_$ts.sql.gz"
    unset MYSQL_PWD
    [ -s "$BACKUP_ROOT/traccar_$ts.sql.gz" ] || die "dump do banco ficou vazio, abortando antes de qualquer mudança"
    log "backup do banco ok: $BACKUP_ROOT/traccar_$ts.sql.gz"

    local release_backup
    release_backup=$(backup_release "$ts")
    log "backup do release atual ok: $release_backup"

    log "parando $SERVICE_NAME..."
    systemctl stop "$SERVICE_NAME"

    cp "$REPO_DIR/target/tracker-server.jar" "$TRACCAR_HOME/"
    rsync -a --delete "$REPO_DIR/target/lib/" "$TRACCAR_HOME/lib/"
    rsync -a --delete "$REPO_DIR/schema/" "$TRACCAR_HOME/schema/"
    rsync -a --delete "$REPO_DIR/templates/" "$TRACCAR_HOME/templates/"

    log "subindo $SERVICE_NAME..."
    systemctl start "$SERVICE_NAME"

    if wait_for_health "http://localhost:$WEB_PORT/api/server" "$HEALTH_TIMEOUT"; then
        log "DEPLOY OK. Backup do banco: $BACKUP_ROOT/traccar_$ts.sql.gz — Backup do release: $release_backup"
    else
        warn "healthcheck falhou, revertendo automaticamente para o release anterior..."
        systemctl stop "$SERVICE_NAME" || true
        restore_release "$release_backup"
        systemctl start "$SERVICE_NAME"
        if wait_for_health "http://localhost:$WEB_PORT/api/server" "$HEALTH_TIMEOUT"; then
            die "ROLLBACK EXECUTADO com sucesso. O deploy novo NÃO subiu, mas o serviço está de volta ao estado anterior. Veja: journalctl -u $SERVICE_NAME -n 200"
        else
            die "ROLLBACK TAMBÉM FALHOU. Intervenção manual necessária. Backups em $release_backup e $BACKUP_ROOT/traccar_$ts.sql.gz"
        fi
    fi
}

# ---------- --rollback ----------

run_rollback() {
    local ts="${1:-}"
    local dir
    if [ -z "$ts" ]; then
        dir=$(ls -1dt "$BACKUP_ROOT"/release_*/ 2>/dev/null | head -n1)
        [ -n "$dir" ] || die "nenhum backup encontrado em $BACKUP_ROOT"
    else
        dir="$BACKUP_ROOT/release_$ts"
    fi

    read -r -p "Vou reverter $SERVICE_NAME para o release em $dir. Digite CONFIRMAR: " confirm
    [ "$confirm" = "CONFIRMAR" ] || die "abortado pelo operador"

    systemctl stop "$SERVICE_NAME"
    restore_release "$dir"
    systemctl start "$SERVICE_NAME"

    local web_port
    web_port=$(conf_value "web.port")
    if wait_for_health "http://localhost:${web_port:-8082}/api/server" "$HEALTH_TIMEOUT"; then
        log "rollback concluído e serviço respondendo."
    else
        die "rollback aplicado mas o serviço não respondeu — cheque journalctl -u $SERVICE_NAME"
    fi
}

# ---------- --web ----------

run_web() {
    local web_project="$REPO_DIR/traccar-web"
    [ -f "$web_project/package.json" ] || die "não achei $web_project/package.json"
    require_cmd npm
    [ -d "$TRACCAR_HOME/web" ] || die "$TRACCAR_HOME/web não existe (TRACCAR_HOME=$TRACCAR_HOME está certo?)"

    local node_version
    node_version=$(node --version 2>/dev/null | grep -oP '(?<=v)[0-9]+' | head -n1)
    [ -n "$node_version" ] && [ "$node_version" -ge 20 ] \
        || die "Node.js 20+ não encontrado (rode 'node --version'). Instale antes de continuar."

    log "instalando dependências e compilando o front (traccar-web)..."
    (cd "$web_project" && npm ci && npm run build)
    # o outDir do Vite nesse projeto é "build" (vite.config.js), não o "dist" padrão
    [ -d "$web_project/build" ] || die "build não gerou $web_project/build"

    local ts dir
    ts=$(date -u +%Y%m%dT%H%M%SZ)
    dir="$BACKUP_ROOT/web_$ts"
    mkdir -p "$dir"
    cp -r "$TRACCAR_HOME/web/." "$dir/"
    log "backup do web/ atual: $dir"

    rsync -a --delete "$web_project/build/" "$TRACCAR_HOME/web/"
    log "WEB OK — front atualizado em $TRACCAR_HOME/web (nenhum serviço foi reiniciado)."
    log "peça pra quem for validar dar um hard-refresh (Ctrl+Shift+R) por causa do cache do navegador."
    log "pra reverter: ./scripts/deploy.sh --web-rollback $ts"
}

run_web_rollback() {
    local ts="${1:-}"
    local dir
    if [ -z "$ts" ]; then
        dir=$(ls -1dt "$BACKUP_ROOT"/web_*/ 2>/dev/null | head -n1)
        [ -n "$dir" ] || die "nenhum backup de web/ encontrado em $BACKUP_ROOT"
    else
        dir="$BACKUP_ROOT/web_$ts"
    fi
    [ -d "$dir" ] || die "backup não encontrado: $dir"

    read -r -p "Vou reverter $TRACCAR_HOME/web para o backup em $dir. Digite CONFIRMAR: " confirm
    [ "$confirm" = "CONFIRMAR" ] || die "abortado pelo operador"

    rsync -a --delete "$dir/" "$TRACCAR_HOME/web/"
    log "web/ revertido a partir de $dir (nenhum serviço precisou reiniciar)."
}

# ---------- entrypoint ----------

case "${1:-}" in
    --stg) run_stg ;;
    --stg-stop) run_stg_stop ;;
    --prod) run_prod ;;
    --rollback) run_rollback "${2:-}" ;;
    --web) run_web ;;
    --web-rollback) run_web_rollback "${2:-}" ;;
    --fix-changelog) run_fix_changelog ;;
    *) usage; exit 1 ;;
esac
