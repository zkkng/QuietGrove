"""Quiet Grove's account bridge. No game-process or NX writes.

Serve using Gunicorn behind nginx. Configuration/secrets belong in the host's
root-owned environment file; the public document root is a separate directory.
"""
import hashlib
import hmac
import json
import os
import re
import secrets
import socket
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

import bcrypt
import pymysql
from flask import Flask, abort, g, jsonify, request, send_from_directory, session
from werkzeug.exceptions import HTTPException
from werkzeug.middleware.proxy_fix import ProxyFix

ROOT = Path(os.environ.get('QG_SITE_ROOT', '../Ellinia')).resolve()
ORIGINS = set(os.environ.get('QG_ORIGINS', 'http://localhost:3000,http://127.0.0.1:3000').split(','))
SECURE = os.environ.get('QG_SECURE_COOKIES', '0') == '1'
BLOCKED_ACCOUNTS = tuple(int(x) for x in os.environ.get('QG_BOT_ACCOUNTS', '2').split(',') if x)
app = Flask(__name__, static_folder=None)
# Gunicorn binds loopback; nginx overwrites X-Forwarded-For with its peer IP.
app.wsgi_app = ProxyFix(app.wsgi_app, x_for=1)
app.config.update(SECRET_KEY=os.environ['QG_SESSION_SECRET'], MAX_CONTENT_LENGTH=16384,
                  SESSION_COOKIE_NAME='qg_csrf', SESSION_COOKIE_HTTPONLY=True,
                  SESSION_COOKIE_SECURE=SECURE, SESSION_COOKIE_SAMESITE='Lax')
DUMMY_HASH = bcrypt.hashpw(b'not-an-account-password', bcrypt.gensalt(rounds=12))


@contextmanager
def database():
    db = pymysql.connect(unix_socket=os.environ.get('QG_DB_SOCKET', '/run/mysqld/mysqld.sock'),
                         user=os.environ.get('QG_DB_USER', 'quietgrove'),
                         password=os.environ['QG_DB_PASSWORD'], database='cosmic',
                         charset='utf8mb4', connect_timeout=3, read_timeout=5, write_timeout=5,
                         cursorclass=pymysql.cursors.DictCursor, autocommit=False)
    try:
        yield db
        db.commit()
    except BaseException:
        db.rollback()
        raise
    finally:
        db.close()


def query(sql, params=()):
    with database() as db, db.cursor() as cur:
        cur.execute(sql, params)
        return cur.fetchall()


def fail(message, status=400):
    abort(status, description=message)


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def data():
    value = request.get_json(silent=True)
    if not isinstance(value, dict):
        fail('Please submit a JSON object.')
    return value


def text_field(value, name, maximum=120):
    result = value.get(name, '')
    if not isinstance(result, str) or len(result) > maximum:
        fail('Please check the form fields.')
    return result


def limited(scope, maximum, minutes=15, account=''):
    # Durable and atomic across workers/restarts; no forwarded IP headers trusted.
    key = digest(scope + ':' + request.remote_addr + ':' + account.lower())
    with database() as db, db.cursor() as cur:
        cur.execute('INSERT INTO web_rate_limits(bucket,hits,expires_at) VALUES(%s,1,DATE_ADD(UTC_TIMESTAMP(),INTERVAL %s MINUTE)) ON DUPLICATE KEY UPDATE hits=IF(expires_at<=UTC_TIMESTAMP(),1,hits+1),expires_at=IF(expires_at<=UTC_TIMESTAMP(),VALUES(expires_at),expires_at)', (key, minutes))
        cur.execute('SELECT hits FROM web_rate_limits WHERE bucket=%s', (key,))
        hits = cur.fetchone()['hits']
    if hits > maximum:
        fail('Too many attempts. Please try again later.', 429)


def password_matches(password, stored):
    if not isinstance(stored, str):
        return False
    if stored.startswith(('$2a$', '$2b$', '$2y$')):
        try:
            return bcrypt.checkpw(password.encode(), stored.encode())
        except ValueError:
            return False
    # Compatibility with Client.login's legacy migration path. Never downgrade a hash.
    for algorithm, length in [('sha1', 40), ('sha512', 128)]:
        if len(stored) == length and re.fullmatch(r'[a-fA-F0-9]+', stored):
            return hmac.compare_digest(hashlib.new(algorithm, password.encode()).hexdigest(), stored.lower())
    return hmac.compare_digest(password.encode(), stored.encode())


def new_password(value):
    if not isinstance(value, str) or not len(value) == 12 or not re.fullmatch(r'[\x21-\x7e]+', value):
        fail('Use a 12-character password: letters, numbers or symbols, without spaces, to fit the classic client.')
    return bcrypt.hashpw(value.encode(), bcrypt.gensalt(rounds=12, prefix=b'2a')).decode()


def cookie_token():
    return request.cookies.get('qg_account', '')


def account(required=True):
    if hasattr(g, 'account'):
        result = g.account
    else:
        token = cookie_token()
        rows = query('SELECT a.id,a.name,a.email,a.nxCredit,a.nxPrepaid,a.maplePoint,a.characterslots,a.loggedin FROM web_sessions s JOIN accounts a ON a.id=s.account_id WHERE s.token_hash=%s AND s.expires_at>UTC_TIMESTAMP() AND a.banned=0 AND a.tempban<=UTC_TIMESTAMP()', (digest(token),)) if re.fullmatch(r'[A-Za-z0-9_-]{43}', token) else []
        result = rows[0] if rows else None
        g.account = result
    if not result and required:
        fail('Please sign in to your Quiet Grove account.', 401)
    return result


def signin(account_id):
    token = secrets.token_urlsafe(32)
    with database() as db, db.cursor() as cur:
        if cookie_token():
            cur.execute('DELETE FROM web_sessions WHERE token_hash=%s', (digest(cookie_token()),))
        cur.execute('INSERT INTO web_sessions(token_hash,account_id,expires_at) VALUES(%s,%s,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 7 DAY))', (digest(token), account_id))
    session.clear()
    session['csrf'] = secrets.token_urlsafe(32)
    response = jsonify(ok=True, csrf=session['csrf'])
    response.set_cookie('qg_account', token, max_age=604800, httponly=True, secure=SECURE, samesite='Lax', path='/')
    return response


@app.before_request
def protect():
    # Host allowlist also prevents DNS-rebinding against this LAN service.
    if request.host not in {origin.split('://', 1)[1] for origin in ORIGINS}:
        fail('This hostname is not configured for Quiet Grove.', 403)
    if request.path.startswith('/api/') and request.method not in ('GET', 'HEAD', 'OPTIONS'):
        if request.headers.get('Origin') not in ORIGINS:
            fail('Open this form from the Quiet Grove website.', 403)
        supplied = request.headers.get('X-CSRF-Token', '')
        if not session.get('csrf') or not hmac.compare_digest(supplied, session['csrf']):
            fail('This form expired. Refresh the page and try again.', 403)


@app.after_request
def headers(response):
    response.headers['X-Content-Type-Options'] = 'nosniff'
    response.headers['X-Frame-Options'] = 'DENY'
    response.headers['Referrer-Policy'] = 'strict-origin-when-cross-origin'
    response.headers['Content-Security-Policy'] = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; media-src 'self'; connect-src 'self'; font-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"
    if request.path.startswith('/api/'):
        response.headers['Cache-Control'] = 'no-store'
    if SECURE:
        response.headers['Strict-Transport-Security'] = 'max-age=31536000'
    return response


@app.errorhandler(HTTPException)
def http_error(error):
    if request.path.startswith('/api/'):
        return jsonify(error=error.description), error.code
    return send_from_directory(ROOT, '404.html'), error.code


@app.errorhandler(pymysql.MySQLError)
def unavailable(error):
    app.logger.error('Database unavailable (type %s, code %s)', type(error).__name__, error.args[0] if error.args else '?')
    return jsonify(error='Account services are temporarily unavailable. Please try again shortly.'), 503


@app.get('/api/session')
def get_session():
    session.setdefault('csrf', secrets.token_urlsafe(32))
    person = account(False)
    return jsonify(csrf=session['csrf'], signedIn=bool(person), username=person['name'] if person else None)


@app.post('/api/register')
def register():
    limited('register', 5, 60)
    value = data()
    username = text_field(value, 'username', 13)
    email = text_field(value, 'email', 45).strip()
    if not re.fullmatch(r'[A-Za-z0-9]{3,12}', username):
        fail('Choose 3–12 letters or numbers for your game account name.')
    if not re.fullmatch(r'[^\s@]+@[^\s@]+\.[^\s@]+', email):
        fail('Enter a valid email address, up to 45 characters.')
    if value.get('acceptRules') is not True:
        fail('Please agree to the server rules and privacy notice.')
    password = new_password(text_field(value, 'password', 12))
    try:
        with database() as db, db.cursor() as cur:
            cur.execute('INSERT INTO accounts(name,password,email,tos,nxCredit,nxPrepaid,maplePoint,gender,characterslots) VALUES(%s,%s,%s,1,0,0,0,10,3)', (username, password, email))
            ident = cur.lastrowid
    except pymysql.IntegrityError:
        fail('That account name is unavailable. Please choose another.', 409)
    return signin(ident)


@app.post('/api/login')
def login():
    value = data()
    username = text_field(value, 'username', 13)
    password = text_field(value, 'password', 72)
    limited('login-ip', 30)
    limited('login-account', 8, account=username)
    rows = query('SELECT id,password,banned,(tempban>UTC_TIMESTAMP()) AS suspended FROM accounts WHERE name=%s', (username,))
    row = rows[0] if rows else None
    valid = password_matches(password, row['password'] if row else DUMMY_HASH.decode())
    if not row or not valid or row['banned'] or row['suspended'] or row['id'] in BLOCKED_ACCOUNTS:
        fail('The account name or password is incorrect, or this account is unavailable.', 401)
    return signin(row['id'])


@app.post('/api/logout')
def logout():
    query('DELETE FROM web_sessions WHERE token_hash=%s', (digest(cookie_token()),))
    session.clear()
    response = jsonify(ok=True)
    response.delete_cookie('qg_account', path='/', secure=SECURE, httponly=True, samesite='Lax')
    return response


def job_name(job):
    names = {0:'Beginner',100:'Warrior',110:'Fighter',111:'Crusader',112:'Hero',120:'Page',121:'White Knight',122:'Paladin',130:'Spearman',131:'Dragon Knight',132:'Dark Knight',200:'Magician',210:'Fire / Poison Wizard',211:'Fire / Poison Mage',212:'Fire / Poison Arch Mage',220:'Ice / Lightning Wizard',221:'Ice / Lightning Mage',222:'Ice / Lightning Arch Mage',230:'Cleric',231:'Priest',232:'Bishop',300:'Bowman',310:'Hunter',311:'Ranger',312:'Bowmaster',320:'Crossbowman',321:'Sniper',322:'Marksman',400:'Thief',410:'Assassin',411:'Hermit',412:'Night Lord',420:'Bandit',421:'Chief Bandit',422:'Shadower',500:'Pirate',510:'Brawler',511:'Marauder',512:'Buccaneer',520:'Gunslinger',521:'Outlaw',522:'Corsair',1000:'Noblesse',1100:'Dawn Warrior',1110:'Dawn Warrior',1111:'Dawn Warrior',1112:'Dawn Warrior',1200:'Blaze Wizard',1210:'Blaze Wizard',1211:'Blaze Wizard',1212:'Blaze Wizard',1300:'Wind Archer',1310:'Wind Archer',1311:'Wind Archer',1312:'Wind Archer',1400:'Night Walker',1410:'Night Walker',1411:'Night Walker',1412:'Night Walker',1500:'Thunder Breaker',1510:'Thunder Breaker',1511:'Thunder Breaker',1512:'Thunder Breaker',2000:'Legend',2100:'Aran',2110:'Aran',2111:'Aran',2112:'Aran'}
    return names.get(job, 'Adventurer')


@app.get('/api/account')
def profile():
    person = account()
    characters = query('SELECT c.id,c.name,c.level,c.job,c.fame,c.world,COALESCE(g.name,\'\') AS guild FROM characters c LEFT JOIN guilds g ON c.guildid=g.guildid WHERE c.accountid=%s ORDER BY c.level DESC,c.id', (person['id'],))
    for character in characters:
        character['jobName'] = job_name(character['job'])
    return jsonify(username=person['name'], email=person['email'], nxCredit=person['nxCredit'] or 0,
                   nxPrepaid=person['nxPrepaid'] or 0, maplePoints=person['maplePoint'] or 0,
                   characterSlots=person['characterslots'], characters=characters)


@app.post('/api/account/password')
def password_change():
    person = account()
    limited('password-change', 5, account=str(person['id']))
    value = data()
    replacement = new_password(text_field(value, 'newPassword', 12))
    current = text_field(value, 'currentPassword', 72)
    with database() as db, db.cursor() as cur:
        cur.execute('SELECT password,loggedin FROM accounts WHERE id=%s FOR UPDATE', (person['id'],))
        row = cur.fetchone()
        if not password_matches(current, row['password']):
            fail('Your current password is incorrect.', 401)
        if row['loggedin'] != 0:
            fail('Log out of the game before changing your password.', 409)
        cur.execute('UPDATE accounts SET password=%s WHERE id=%s', (replacement, person['id']))
        cur.execute('DELETE FROM web_sessions WHERE account_id=%s', (person['id'],))
    return signin(person['id'])


from ranking_service import install_rankings
install_rankings(app, query, job_name, BLOCKED_ACCOUNTS)


@app.get('/api/status')
def status():
    ports = {'login':8484,'channel1':7575,'channel2':7576,'channel3':7577}
    listeners = {}
    for name,port in ports.items():
        try:
            with socket.create_connection((os.environ.get('QG_GAME_HOST', '127.0.0.1'),port),timeout=.2):
                listeners[name] = True
        except OSError:
            listeners[name] = False
    try:
        exclusions = (' AND a.id NOT IN ('+','.join(['%s']*len(BLOCKED_ACCOUNTS))+')') if BLOCKED_ACCOUNTS else ''
        counts = query('SELECT COUNT(*) AS n FROM accounts a WHERE a.loggedin=2 AND a.banned=0 AND a.tempban<=UTC_TIMESTAMP()'
                       ' AND NOT EXISTS (SELECT 1 FROM characters c WHERE c.accountid=a.id AND c.gm>0)'+exclusions,
                       BLOCKED_ACCOUNTS)[0]
        db_online, online = True, counts['n']
    except pymysql.MySQLError:
        db_online, online = False, None
    state = 'online' if listeners['login'] and listeners['channel1'] and db_online else 'partial' if any(listeners.values()) else 'offline'
    return jsonify(state=state,accountServices=db_online,listeners=listeners,reportedOnlineAccounts=online,
                   checkedAt=datetime.now(timezone.utc).isoformat(),note='Listener checks measure reachability, not a completed in-game login. Online accounts are database-reported.')


@app.route('/api/tickets', methods=['GET','POST'])
def tickets():
    person = account()
    if request.method == 'GET':
        return jsonify(tickets=query('SELECT id,category,subject,body,status,staff_reply AS reply,created_at AS createdAt FROM web_tickets WHERE account_id=%s ORDER BY id DESC LIMIT 50', (person['id'],)))
    limited('ticket',5,60, str(person['id']))
    value = data()
    category = text_field(value,'category',30)
    subject = text_field(value,'subject',120).strip()
    body = text_field(value,'body',4000).strip()
    if category not in ('account','bug','player-report','feedback') or len(subject)<4 or len(body)<15:
        fail('Choose a category and include a subject and at least 15 characters of detail.')
    with database() as db, db.cursor() as cur:
        cur.execute('INSERT INTO web_tickets(account_id,category,subject,body) VALUES(%s,%s,%s,%s)',(person['id'],category,subject,body))
        ident=cur.lastrowid
    return jsonify(ok=True,id=ident),201


@app.get('/api/config')
def config():
    # Explicit reviewed public projection, never expose the server YAML or secrets.
    return jsonify(json.loads((ROOT/'data'/'server.json').read_text()))


@app.get('/downloads/<path:name>')
def download(name):
    directory = os.environ.get('QG_DOWNLOAD_ROOT')
    if not directory or name not in ('QuietGrove-client.zip',):
        abort(404)
    return send_from_directory(directory,name,as_attachment=True)


@app.get('/')
def home():
    return send_from_directory(ROOT,'index.html')


@app.get('/<path:path>')
def static_file(path):
    if path in ('roadmap','news','rankings','ledger','account','play','guide','rules','privacy','support','shop','status','community'):
        return send_from_directory(ROOT,'portal.html')
    # Prevent publishing source, logs, hidden files, backups or runtime configuration.
    if any(part.startswith('.') for part in Path(path).parts) or Path(path).suffix.lower() not in ('.html','.css','.js','.json','.png','.jpg','.jpeg','.webp','.svg','.mp3','.woff2','.ico'):
        abort(404)
    return send_from_directory(ROOT,path)
