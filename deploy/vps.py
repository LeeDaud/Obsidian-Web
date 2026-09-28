"""Deployment transport. Credentials are read locally and never logged or packed."""
import argparse
import getpass
import json
from pathlib import Path
import re
import shlex
import sys
import tarfile
import tempfile
import time

import paramiko

PROJECT = Path(__file__).resolve().parents[1]
ROOT = '/opt/echo'
RELEASE = ROOT + '/releases/20260928'


def connect(info):
    text = Path(info).read_text(encoding='utf-8-sig')
    host = re.search(r'IP Address:\s*(\S+)', text).group(1)
    user = re.search(r'Username:\s*(\S+)', text).group(1)
    password = re.search(r'(?:Password|密码)\s*[:：]\s*(.+)', text, re.I).group(1).strip().strip('`')
    for attempt in range(3):
        client = paramiko.SSHClient()
        client.load_system_host_keys()
        client.load_host_keys(str(Path.home() / '.ssh/known_hosts'))
        try:
            client.connect(host, username=user, password=password, look_for_keys=False, allow_agent=False, timeout=20)
            client.get_transport().set_keepalive(15)
            return client
        except (EOFError, paramiko.SSHException):
            client.close()
            if attempt == 2:
                raise
            time.sleep(15 * (attempt + 1))


def run(client, command):
    _, out, err = client.exec_command(command, timeout=900)
    for line in out:
        # Paramiko decodes remote output as UTF-8, but Windows consoles may still
        # use a legacy code page. Replace only characters the console cannot show
        # so deployment output cannot abort an otherwise healthy remote build.
        try:
            print(line, end='', flush=True)
        except UnicodeEncodeError:
            encoding = getattr(sys.stdout, 'encoding', None) or 'utf-8'
            print(line.encode(encoding, errors='replace').decode(encoding), end='', flush=True)
    errors = err.read().decode()
    if errors:
        print(errors, flush=True)
    code = out.channel.recv_exit_status()
    if code:
        raise RuntimeError(f'Remote operation failed, exit {code}')


def pack():
    archive = Path(tempfile.gettempdir()) / 'echo-deploy-20260921.tar.gz'
    roots = ['package.json', 'package-lock.json', 'src', 'deploy',
             'upstream/ignis/package.json', 'upstream/ignis/package-lock.json',
             'upstream/ignis/LICENSE', 'upstream/ignis/images',
             'upstream/ignis/packages/server-core', 'upstream/ignis/apps/ignis-server',
             'upstream/ignis/packages/shim/dist', 'upstream/ignis/packages/ui/dist',
             'upstream/memos']
    with tarfile.open(archive, 'w:gz') as tar:
        for name in roots:
            root = PROJECT / name
            for file in ([root] if root.is_file() else root.rglob('*')):
                rel = file.relative_to(PROJECT)
                if not file.is_file() or file.is_symlink():
                    continue
                if any(part in {'node_modules', '.git', '.claude', '.codex', '__pycache__'} for part in rel.parts):
                    continue
                if rel.parts[:2] == ('upstream', 'memos') and 'dist' in rel.parts:
                    continue
                if file.name.startswith('.env') or '.test.' in file.name or file.suffix == '.log':
                    continue
                tar.add(file, arcname=rel.as_posix(), recursive=False)
    return archive


def upload(client):
    archive = pack()
    run(client, 'install -d -m 755 /opt/echo /opt/echo/releases ' + RELEASE +
        ' /opt/echo/obsidian /opt/memos; install -d -m 700 /opt/echo/data /opt/echo/locks /opt/echo/secrets '
        '/opt/memos/data /opt/memos/echo-queue /opt/memos/secrets; '
        'if test ! -s /opt/memos/secrets/bridge_token; then umask 077; openssl rand -hex 32 > /opt/memos/secrets/bridge_token; fi; '
        'install -o 1000 -g 1000 -m 400 /opt/memos/secrets/bridge_token /opt/memos/secrets/bridge_token_echo; '
        'install -o 10001 -g 10001 -m 400 /opt/memos/secrets/bridge_token /opt/memos/secrets/bridge_token_memos; '
        'chmod 400 /opt/memos/secrets/bridge_token; chown 10001:10001 /opt/memos/data; chown 1000:1000 /opt/memos/echo-queue')
    sftp = client.open_sftp()
    sftp.put(str(archive), '/opt/echo/release.tar.gz')
    try:
        sftp.stat('/opt/echo/obsidian/index.html')
    except FileNotFoundError:
        try:
            sftp.stat('/opt/jike/obsidian/index.html')
        except FileNotFoundError:
            asar = Path(tempfile.gettempdir()) / 'ignis-capture-runtime/obsidian.asar.gz'
            sftp.put(str(asar), '/opt/echo/obsidian.asar.gz')
    try:
        sftp.stat('/opt/echo/secrets/github_token')
    except FileNotFoundError:
        with sftp.file('/opt/echo/secrets/github_token', 'w') as file:
            file.write('')
        sftp.chmod('/opt/echo/secrets/github_token', 0o400)
    sftp.close()
    run(client, 'tar -xzf /opt/echo/release.tar.gz -C ' + RELEASE +
        ' && chown 1000:1000 /opt/echo/data /opt/echo/locks /opt/echo/secrets/github_token')


def migrate(client):
    run(client, 'docker stop echo-capture >/dev/null 2>&1 || true; '
        'test -d /opt/jike/data && docker stop jike-capture >/dev/null 2>&1 || true; '
        'if test ! -e /opt/echo/migration.done; then '
        'cp -a /opt/jike/data/. /opt/echo/data/ && cp -a /opt/jike/obsidian/. /opt/echo/obsidian/ && '
        'cp -a /opt/jike/secrets/. /opt/echo/secrets/ && cp -a /opt/jike/locks/. /opt/echo/locks/ && '
        'touch /opt/echo/migration.done; fi; '
        'owner=/opt/echo/data/queue/owner.json; old=\'{"repo":"LeeDaud/Jike","branch":"main"}\'; '
        'new=\'{"repo":"LeeDaud/Echo","branch":"main"}\'; current=$(cat "$owner"); '
        'if test "$current" = "$old"; then printf "%s" "$new" > "$owner.new" && '
        'chown 1000:1000 "$owner.new" && chmod 600 "$owner.new" && mv "$owner.new" "$owner"; '
        'elif test "$current" != "$new"; then echo "Unexpected queue owner" >&2; exit 1; fi; '
        'chown -R 1000:1000 /opt/echo/data /opt/echo/locks /opt/echo/secrets && '
        'test -s /opt/echo/secrets/github_token && test -e /opt/echo/obsidian/index.html && '
        'printf "old_records="; find /opt/jike/data/queue -maxdepth 1 -name "*.json" | wc -l; '
        'printf "echo_records="; find /opt/echo/data/queue -maxdepth 1 -name "*.json" | wc -l; '
        'printf "owner="; cat /opt/echo/data/queue/owner.json; printf "\\n"')


def proxy(client):
    sftp = client.open_sftp()
    target = '/opt/vaultwarden/Caddyfile'
    with sftp.open(target) as file:
        original = file.read()
    old = b'\njike.leedaud.xyz {\n    encode zstd gzip\n    reverse_proxy jike-capture:4319\n}\n'
    replacement = (b'\necho.leedaud.xyz {\n    encode zstd gzip\n    reverse_proxy echo-capture:4319\n}\n'
        b'\njike.leedaud.xyz {\n    redir https://echo.leedaud.xyz{uri} permanent\n}\n')
    if b'echo.leedaud.xyz' in original:
        raise RuntimeError('Echo site already present; inspect before changing it.')
    if old not in original:
        raise RuntimeError('Expected legacy site block was not found.')
    candidate = original.replace(old, replacement, 1)
    with sftp.file('/opt/echo/Caddyfile.before-echo', 'wx') as file:
        file.write(original)
    with sftp.file('/opt/echo/Caddyfile.candidate', 'w') as file:
        file.write(candidate)
    # Validate a candidate without mutating the live bind-mounted file.
    run(client, 'docker cp /opt/echo/Caddyfile.candidate caddy:/tmp/echo.Caddyfile && '
        'docker exec caddy caddy validate --config /tmp/echo.Caddyfile --adapter caddyfile')
    try:
        # Preserve inode: the existing container bind mounts this exact file.
        with sftp.file(target, 'w') as file:
            file.write(candidate)
        run(client, 'docker exec caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile')
    except Exception:
        with sftp.file(target, 'w') as file:
            file.write(original)
        run(client, 'docker exec caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile')
        raise
    finally:
        sftp.close()


def memos_proxy(client):
    sftp = client.open_sftp()
    target = '/opt/vaultwarden/Caddyfile'
    with sftp.open(target) as file:
        original = file.read()
    if b'memos.leedaud.xyz' in original:
        raise RuntimeError('Memos site already present; inspect before changing it.')
    candidate = original.rstrip() + (b'\n\nmemos.leedaud.xyz {\n    encode zstd gzip\n'
        b'    reverse_proxy echo-memos:5230\n}\n')
    with sftp.file('/opt/memos/Caddyfile.before-memos', 'wx') as file:
        file.write(original)
    with sftp.file('/opt/memos/Caddyfile.candidate', 'w') as file:
        file.write(candidate)
    run(client, 'docker cp /opt/memos/Caddyfile.candidate caddy:/tmp/memos.Caddyfile && '
        'docker exec caddy caddy validate --config /tmp/memos.Caddyfile --adapter caddyfile')
    try:
        with sftp.file(target, 'w') as file:
            file.write(candidate)
        run(client, 'docker exec caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile')
    except Exception:
        with sftp.file(target, 'w') as file:
            file.write(original)
        run(client, 'docker exec caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile')
        raise
    finally:
        sftp.close()


def github_token(client, token_file):
    source = Path(token_file)
    token = source.read_text(encoding='utf-8-sig').strip()
    if not token.startswith(('github_pat_', 'ghp_')) or len(token) < 40:
        raise ValueError('GitHub token format is invalid.')
    sftp = client.open_sftp()
    try:
        with sftp.file('/opt/echo/secrets/github_token.new', 'w') as file:
            file.write(token + '\n')
        sftp.chmod('/opt/echo/secrets/github_token.new', 0o400)
        sftp.posix_rename('/opt/echo/secrets/github_token.new', '/opt/echo/secrets/github_token')
    finally:
        sftp.close()
    run(client, 'chown 1000:1000 /opt/echo/secrets/github_token && '
        'token=$(cat /opt/echo/secrets/github_token) && '
        'test "$(curl -sS -o /dev/null -w \'%{http_code}\' -H "Authorization: Bearer $token" '
        '-H "Accept: application/vnd.github+json" https://api.github.com/repos/LeeDaud/Echo)" = 200')
    source.write_text('', encoding='utf-8')
    print('GitHub token verified, installed, and removed from the local staging file.', flush=True)


def github_check(client, filename, marker):
    if not re.fullmatch(r'(?:\d{14}|\d{8}-\d{6})\.md', filename):
        raise ValueError('Synthetic note filename is invalid.')
    script = (
        "const fs=require('fs'),marker=process.argv[1],filename=process.argv[2];"
        "fetch('https://api.github.com/repos/LeeDaud/Echo/contents/'+filename+'?ref=main',{headers:{"
        "Authorization:'Bearer '+fs.readFileSync('/run/secrets/github_token','utf8').trim(),"
        "Accept:'application/vnd.github+json'}}).then(async r=>{if(!r.ok)throw Error(String(r.status));return r.json()})"
        ".then(x=>{const body=Buffer.from(x.content,'base64').toString('utf8');if(!body.includes(marker))process.exit(2);"
        "console.log(JSON.stringify({path:x.path,sha:x.sha,bytes:Buffer.byteLength(body),marker:true}))})"
    )
    command = ('container=echo-capture; docker inspect echo-capture >/dev/null 2>&1 || container=jike-capture; '
        'docker exec "$container" node -e ' + shlex.quote(script) + ' ' + shlex.quote(marker) + ' ' + shlex.quote(filename))
    run(client, command)


def echo_smoke(client, marker):
    if not re.fullmatch(r'[0-9a-f-]{36}', marker):
        raise ValueError('Synthetic marker is invalid.')
    script = (
        "const marker=process.argv[1],origin='https://echo.leedaud.xyz',base=origin,"
        "createdAt=new Date().toISOString(),headers={'Content-Type':'application/json',Origin:origin};"
        "const post=async(p,b)=>{const r=await fetch(base+p,{method:'POST',headers,body:JSON.stringify(b)});"
        "const x=await r.json();if(!r.ok)throw Error(JSON.stringify(x));return x};"
        "(async()=>{const opened=await post('/api/capture/open',{id:marker,createdAt});"
        "const content='# Echo 同步验收\\n\\n合成测试笔记，可在验收后删除。\\n\\n标识：'+marker+'\\n';"
        "await post('/api/fs/writeFile',{vault:'Inbox',path:opened.filename,content,capture:{id:marker,createdAt,revision:1,content}});"
        "let state;for(let i=0;i<18;i++){await new Promise(r=>setTimeout(r,5000));state=await post('/api/capture/heartbeat',{id:marker});"
        "if(state.state==='backed-up'||state.error)break}console.log(JSON.stringify({id:marker,filename:opened.filename,...state}))})()"
    )
    run(client, 'docker exec echo-capture node -e ' + shlex.quote(script) + ' ' + shlex.quote(marker))


def echo_note_status(client, marker):
    if not re.fullmatch(r'[0-9a-f-]{36}', marker):
        raise ValueError('Synthetic marker is invalid.')
    sftp = client.open_sftp()
    try:
        with sftp.file('/opt/echo/data/queue/' + marker + '.json', 'r') as file:
            note = json.loads(file.read().decode('utf-8'))
    finally:
        sftp.close()
    print(json.dumps({'revision': note.get('revision'), 'publishedRevision': note.get('publishedRevision'),
        'retired': bool(note.get('retired')), 'contentPresent': 'content' in note,
        'deliveryPresent': bool(note.get('delivery'))}), flush=True)


def memos_init(client):
    script = r'''
import json, os, secrets, subprocess

secret_path = "/opt/memos/secrets/initial_admin"
base = "http://127.0.0.1:5230"

def request(path, method="GET", body=None, token=None):
    command = ["docker", "exec", "-i", "echo-memos", "curl", "-fsS", "-X", method,
               "-H", "Content-Type: application/json"]
    if token:
        command += ["-H", "Authorization: Bearer " + token]
    if body is not None:
        command += ["--data-binary", "@-"]
    command += [base + path]
    result = subprocess.run(command, input=None if body is None else json.dumps(body).encode(),
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
    return json.loads(result.stdout or b"{}")

profile = request("/api/v1/instance/profile")
if profile.get("needsSetup"):
    credentials = {"username": "lee", "password": secrets.token_urlsafe(24)}
    fd = os.open(secret_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
    with os.fdopen(fd, "w") as output:
        json.dump(credentials, output)
        output.write("\n")
    request("/api/v1/users", "POST", {
        "username": credentials["username"], "password": credentials["password"],
        "role": "ADMIN", "state": "NORMAL", "displayName": "Lee"
    })
else:
    with open(secret_path, encoding="utf-8") as source:
        credentials = json.load(source)

signin = request("/api/v1/auth/signin", "POST", {"passwordCredentials": credentials})
token = signin["accessToken"]
request("/api/v1/instance/settings/GENERAL", "PATCH", {
    "name": "instance/settings/GENERAL",
    "generalSetting": {"disallowUserRegistration": True, "disallowPasswordAuth": False}
}, token)
request("/api/v1/instance/settings/ACCESS", "PATCH", {
    "name": "instance/settings/ACCESS",
    "accessSetting": {"accessMode": "INSTANCE_ACCESS_MODE_PRIVATE"}
}, token)
profile = request("/api/v1/instance/profile")
assert not profile.get("needsSetup")
assert profile.get("accessMode") == "INSTANCE_ACCESS_MODE_PRIVATE"
print("memos_initialized=true registration_closed=true access_mode=private credentials_file=" + secret_path)
'''
    run(client, 'python3 -c ' + shlex.quote(script))


def memos_credentials(client, username, password):
    if not re.fullmatch(r'[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,34}[a-zA-Z0-9])?', username):
        raise ValueError('Invalid Memos username.')
    if len(password) < 8 or len(password) > 128:
        raise ValueError('Memos password must contain 8 to 128 characters.')
    desired_path = '/opt/memos/secrets/desired_admin.new'
    sftp = client.open_sftp()
    try:
        with sftp.file(desired_path, 'w') as output:
            output.write(json.dumps({'username': username, 'password': password}) + '\n')
        sftp.chmod(desired_path, 0o400)
    finally:
        sftp.close()
    script = r'''
import json, os, subprocess

current_path = "/opt/memos/secrets/initial_admin"
desired_path = "/opt/memos/secrets/desired_admin.new"
base = "http://127.0.0.1:5230"

def request(path, method="GET", body=None, token=None, check=True):
    command = ["docker", "exec", "-i", "echo-memos", "curl", "-fsS", "-X", method,
               "-H", "Content-Type: application/json"]
    if token:
        command += ["-H", "Authorization: Bearer " + token]
    if body is not None:
        command += ["--data-binary", "@-"]
    command += [base + path]
    result = subprocess.run(command, input=None if body is None else json.dumps(body).encode(),
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if check and result.returncode:
        raise RuntimeError("Memos API request failed with HTTP error")
    return result.returncode, json.loads(result.stdout or b"{}")

try:
    with open(current_path, encoding="utf-8") as source:
        current = json.load(source)
    with open(desired_path, encoding="utf-8") as source:
        desired = json.load(source)
    _, signin = request("/api/v1/auth/signin", "POST", {"passwordCredentials": current})
    token = signin["accessToken"]
    old_username = current["username"]
    request("/api/v1/users/" + old_username + "?updateMask=username,password,display_name", "PATCH", {
        "name": "users/" + old_username,
        "username": desired["username"],
        "password": desired["password"],
        "displayName": desired["username"]
    }, token)
    request("/api/v1/auth/signin", "POST", {"passwordCredentials": desired})
    if current != desired:
        old_code, _ = request("/api/v1/auth/signin", "POST", {"passwordCredentials": current}, check=False)
        if old_code == 0:
            raise RuntimeError("Old Memos credentials still authenticate")
    os.replace(desired_path, current_path)
    os.chmod(current_path, 0o400)
    print("memos_credentials_updated=true new_login_verified=true")
finally:
    if os.path.exists(desired_path):
        os.remove(desired_path)
'''
    run(client, 'python3 -c ' + shlex.quote(script))


def memos_smoke(client):
    script = r'''
import base64, json, pathlib, subprocess, time, urllib.parse, urllib.request, uuid

base = "http://127.0.0.1:5230"
credentials = json.loads(pathlib.Path("/opt/memos/secrets/initial_admin").read_text())
marker = str(uuid.uuid4())
image_bytes = base64.b64decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")

def request(path, method="GET", body=None, token=None):
    command = ["docker", "exec", "-i", "echo-memos", "curl", "-fsS", "-X", method,
               "-H", "Content-Type: application/json"]
    if token:
        command += ["-H", "Authorization: Bearer " + token]
    if body is not None:
        command += ["--data-binary", "@-"]
    command += [base + path]
    result = subprocess.run(command, input=None if body is None else json.dumps(body).encode(),
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
    return json.loads(result.stdout or b"{}")

signin = request("/api/v1/auth/signin", "POST", {"passwordCredentials": credentials})
token = signin["accessToken"]
attachment = request("/api/v1/attachments", "POST", {
    "filename": "pixel.gif", "type": "image/gif", "content": base64.b64encode(image_bytes).decode()
}, token)
attachment_uid = attachment["name"].split("/", 1)[1]
content = "# Memos 同步验收\n\n合成测试笔记，用于核验 Memos → Echo → GitHub。\n\n![](/file/attachments/" + attachment_uid + "/pixel.gif)\n\n标识：" + marker + "\n"
memo = request("/api/v1/memos", "POST", {
    "state": "NORMAL", "content": content, "visibility": "PRIVATE", "attachments": [attachment]
}, token)
memo_uid = memo["name"].split("/", 1)[1]
record = None
for _ in range(25):
    for file in pathlib.Path("/opt/memos/echo-queue").glob("*.json"):
        candidate = json.loads(file.read_text())
        if candidate.get("memo") == memo["name"]:
            record = candidate
            break
    if record and record["state"] == "verified":
        break
    if record and record["state"] == "conflict":
        raise RuntimeError("Synthetic Memos delivery conflicted")
    time.sleep(2)
if not record or record["state"] != "verified":
    raise RuntimeError("Synthetic Memos delivery was not verified in time")

markdown = next(item for item in record["delivery"]["files"] if item["path"].startswith("00_Inbox/") and item["path"].endswith(".md"))
image = next(item for item in record["delivery"]["files"] if item["path"].startswith("attachments/memos/") and item["path"].endswith(".gif"))
github_token = pathlib.Path("/opt/echo/secrets/github_token").read_text().strip()
def github_content(path):
    route = "https://api.github.com/repos/LeeDaud/Echo/contents/" + urllib.parse.quote(path, safe="/") + "?ref=" + record["receipt"]["commit"]
    github_request = urllib.request.Request(route, headers={"Authorization": "Bearer " + github_token,
        "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"})
    with urllib.request.urlopen(github_request, timeout=20) as response:
        return base64.b64decode(json.load(response)["content"])
remote_content = github_content(markdown["path"]).decode()
if marker not in remote_content or "![](" + image["path"] + ")" not in remote_content:
    raise RuntimeError("GitHub content verification failed")
if github_content(image["path"]) != image_bytes:
    raise RuntimeError("GitHub attachment verification failed")
print(json.dumps({"marker": marker, "memo": memo["name"], "submissionId": record["delivery"]["submissionId"],
    "state": record["state"], "path": markdown["path"], "commit": record["receipt"]["commit"],
    "attachmentPath": image["path"], "githubContentVerified": True, "githubAttachmentVerified": True}))
'''
    run(client, 'python3 -c ' + shlex.quote(script))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['upload', 'build', 'build-echo', 'resources', 'migrate', 'start', 'proxy', 'memos-proxy', 'memos-init', 'memos-credentials', 'memos-smoke', 'github-token', 'github-check', 'echo-smoke', 'echo-note', 'logs', 'check'])
    parser.add_argument('--ssh-info', required=True)
    parser.add_argument('--token-file')
    parser.add_argument('--filename')
    parser.add_argument('--marker')
    args = parser.parse_args()
    ssh = connect(args.ssh_info)
    try:
        if args.action == 'upload':
            upload(ssh)
        elif args.action == 'build':
            run(ssh, 'cd ' + shlex.quote(RELEASE) + ' && docker build -f deploy/Dockerfile -t echo-capture:20260928 . 2>&1 && '
                'docker build -f deploy/Dockerfile.memos -t echo-memos:20260928 . 2>&1')
        elif args.action == 'build-echo':
            run(ssh, 'cd ' + shlex.quote(RELEASE) + ' && docker build -f deploy/Dockerfile -t echo-capture:20260928 . 2>&1')
        elif args.action == 'resources':
            run(ssh, 'test ! -e /opt/echo/obsidian/index.html && gunzip -c /opt/echo/obsidian.asar.gz > /opt/echo/obsidian.asar && '
                'docker run --rm --user root --entrypoint sh -v /opt/echo:/runtime echo-capture:20260928 '
                '-c "npm exec --yes --package=@electron/asar -- asar extract /runtime/obsidian.asar /runtime/obsidian" 2>&1')
        elif args.action == 'migrate':
            migrate(ssh)
        elif args.action == 'start':
            run(ssh, 'docker compose -p echo -f ' + RELEASE + '/deploy/compose.yaml up -d 2>&1')
        elif args.action == 'proxy':
            proxy(ssh)
        elif args.action == 'memos-proxy':
            memos_proxy(ssh)
        elif args.action == 'memos-init':
            memos_init(ssh)
        elif args.action == 'memos-credentials':
            username = input('Memos username: ').strip()
            password = getpass.getpass('Memos password: ')
            memos_credentials(ssh, username, password)
        elif args.action == 'memos-smoke':
            memos_smoke(ssh)
        elif args.action == 'github-token':
            if not args.token_file:
                parser.error('--token-file is required for github-token')
            github_token(ssh, args.token_file)
        elif args.action == 'github-check':
            if not args.filename or not args.marker:
                parser.error('--filename and --marker are required for github-check')
            github_check(ssh, args.filename, args.marker)
        elif args.action == 'echo-smoke':
            if not args.marker:
                parser.error('--marker is required for echo-smoke')
            echo_smoke(ssh, args.marker)
        elif args.action == 'echo-note':
            if not args.marker:
                parser.error('--marker is required for echo-note')
            echo_note_status(ssh, args.marker)
        elif args.action == 'logs':
            run(ssh, 'docker logs --tail 100 echo-capture 2>&1; docker logs --tail 100 echo-memos 2>&1; '
                "docker exec echo-capture grep -n normalizeMarkdownPath /app/src/server/memos/queue.ts; "
                "grep -n 'Path:' /opt/echo/releases/20260928/upstream/memos/server/echo_bridge.go | tail -2")
        else:
            run(ssh, 'docker image inspect echo-capture:20260928 --format "echo_image={{.Id}}"; '
                'docker image inspect echo-memos:20260928 --format "memos_image={{.Id}}"; '
                'docker inspect echo-capture --format "echo_running_image={{.Image}}"; '
                'docker inspect echo-memos --format "memos_running_image={{.Image}}"; '
                'docker ps --format "{{.Names}} {{.Status}} {{.Ports}}"; '
                'curl -s -o /dev/null -w "echo_anonymous=%{http_code}\\n" https://echo.leedaud.xyz; '
                'curl -s -o /dev/null -w "memos=%{http_code}\\n" https://memos.leedaud.xyz; '
                'curl -s -o /dev/null -w "legacy_redirect=%{http_code}\\n" https://jike.leedaud.xyz; '
                'curl -s -o /dev/null -w "vaultwarden=%{http_code}\\n" https://bitwarden.leedaud.xyz')
    finally:
        ssh.close()
