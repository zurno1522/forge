"""Private, disposable SQLite state for decision validation and export.

Pickle is used only for values produced in this process from validated JSON; this
module never opens a caller-supplied database or deserializes external pickle data.
"""
from collections.abc import MutableMapping, MutableSet
import json
import pickle
import sqlite3
import tempfile
from pathlib import Path


class DiskMap(MutableMapping):
    def __init__(self, store, namespace):
        self.store, self.namespace = store, namespace

    def __getitem__(self, key):
        row = self.store.db.execute('SELECT value FROM state WHERE ns=? AND key=?', (self.namespace, json.dumps(key))).fetchone()
        if row is None:
            raise KeyError(key)
        return pickle.loads(row[0])

    def __setitem__(self, key, value):
        self.store.db.execute('''INSERT INTO state(ns,key,ordinal,value)
            VALUES(?,?,(SELECT COALESCE(MAX(ordinal),-1)+1 FROM state WHERE ns=?),?)
            ON CONFLICT(ns,key) DO UPDATE SET value=excluded.value''',
            (self.namespace, json.dumps(key), self.namespace, pickle.dumps(value, protocol=5)))

    def __delitem__(self, key):
        if not self.store.db.execute('DELETE FROM state WHERE ns=? AND key=?', (self.namespace, json.dumps(key))).rowcount:
            raise KeyError(key)

    def __iter__(self):
        for row in self.store.db.execute('SELECT key FROM state WHERE ns=? ORDER BY ordinal', (self.namespace,)):
            yield json.loads(row[0])

    def __len__(self):
        return self.store.db.execute('SELECT COUNT(*) FROM state WHERE ns=?', (self.namespace,)).fetchone()[0]


class DiskSet(MutableSet):
    def __init__(self, store, namespace):
        self.values = DiskMap(store, namespace)

    def __contains__(self, value):
        return value in self.values

    def __len__(self):
        return len(self.values)

    def __iter__(self):
        return iter(self.values)

    def add(self, value):
        self.values[value] = True

    def discard(self, value):
        if value in self.values:
            del self.values[value]


class DiskList(list):
    """Reiterable, JSON-streamable sequence; no payloads live in the base list."""
    def __init__(self, store, namespace):
        self.store, self.namespace = store, namespace

    def __len__(self):
        return self.store.db.execute('SELECT COALESCE(MAX(ordinal),-1)+1 FROM state WHERE ns=?', (self.namespace,)).fetchone()[0]

    def append(self, value):
        self.store.db.execute('''INSERT INTO state(ns,key,ordinal,value)
            SELECT ?,CAST(COALESCE(MAX(ordinal),-1)+1 AS TEXT),COALESCE(MAX(ordinal),-1)+1,?
            FROM state WHERE ns=?''', (self.namespace, pickle.dumps(value, protocol=5), self.namespace))

    def __iter__(self):
        for row in self.store.db.execute('SELECT value FROM state WHERE ns=? ORDER BY ordinal', (self.namespace,)):
            yield pickle.loads(row[0])


class Games(DiskMap):
    def __getitem__(self, key):
        game = super().__getitem__(key)
        prefix = self.namespace + ':' + str(key)
        game.update(decisions=DiskList(self.store, prefix + ':decisions'),
                    decisionIds=DiskSet(self.store, prefix + ':decisionIds'),
                    decisionContexts=DiskMap(self.store, prefix + ':decisionContexts'),
                    choiceCandidates=DiskSet(self.store, prefix + ':choiceCandidates'),
                    choiceAssessments=DiskMap(self.store, prefix + ':choiceAssessments'),
                    choices=DiskMap(self.store, prefix + ':choices'),
                    actions=DiskMap(self.store, prefix + ':actions'))
        return game

    def __setitem__(self, key, value):
        super().__setitem__(key, {k: v for k, v in value.items() if k not in ('decisions', 'decisionIds', 'actions', 'choices', 'decisionContexts', 'choiceCandidates', 'choiceAssessments')})

    def setdefault(self, key, default=None):
        if key not in self:
            self[key] = default
        return self[key]

    def ordered_keys(self):
        for row in self.store.db.execute('SELECT key FROM state WHERE ns=? ORDER BY CAST(key AS INTEGER)', (self.namespace,)):
            yield json.loads(row[0])


class Runs(DiskList):
    def __iter__(self):
        for slot, run in enumerate(super().__iter__()):
            run['games'] = self.store.games(slot)
            yield run


class DiskCounter(dict):
    def __init__(self, store, namespace, common=False):
        self.store, self.namespace, self.common = store, namespace, common
        self.position = 0

    def update(self, values):
        for value in values:
            # Counter keys in the schema are strings or nonnegative integer counts/X.
            sort_key = value if isinstance(value, str) else f'{len(str(value)):020d}:{value}'
            self.store.db.execute('''INSERT INTO counts(ns,key,sort_key,ordinal,count) VALUES(?,?,?,?,1)
                ON CONFLICT(ns,key) DO UPDATE SET count=count+1''',
                (self.namespace, json.dumps(value), sort_key, self.position))
            self.position += 1

    def items(self):
        order = 'count DESC,ordinal' if self.common else 'ordinal'
        for key, count in self.store.db.execute('SELECT key,count FROM counts WHERE ns=? ORDER BY ' + order, (self.namespace,)):
            yield json.loads(key), count

    def sorted_items(self):
        for key, count in self.store.db.execute('SELECT key,count FROM counts WHERE ns=? ORDER BY sort_key', (self.namespace,)):
            yield json.loads(key), count

    def __iter__(self):
        return (key for key, _ in self.items())

    def __len__(self):
        return self.store.db.execute('SELECT COUNT(*) FROM counts WHERE ns=?', (self.namespace,)).fetchone()[0]


class Spool:
    def __enter__(self):
        self.directory = tempfile.TemporaryDirectory(prefix='forge-decisions-')
        try:
            self.db = sqlite3.connect(Path(self.directory.name) / 'spool.sqlite')
            self.db.executescript('''PRAGMA cache_size=-2048; PRAGMA temp_store=FILE;
                PRAGMA mmap_size=0; PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;
                CREATE TABLE state(ns TEXT,key TEXT,ordinal INTEGER,value BLOB,PRIMARY KEY(ns,key));
                CREATE INDEX state_order ON state(ns,ordinal);
                CREATE TABLE counts(ns TEXT,key TEXT,sort_key TEXT,ordinal INTEGER,count INTEGER,PRIMARY KEY(ns,key));
                CREATE INDEX count_order ON counts(ns,ordinal);
                CREATE INDEX count_frequency ON counts(ns,count DESC,ordinal);
                CREATE TABLE groups(key TEXT PRIMARY KEY,deck TEXT,hash TEXT,pilot TEXT,decisions INTEGER,
                    passes INTEGER,assessments INTEGER,censored INTEGER);''')
            self.runs = Runs(self, 'runs')
            self.rows = DiskList(self, 'rows')
            self.run_ids = DiskSet(self, 'run_ids')
            return self
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def __exit__(self, *_):
        try:
            if hasattr(self, 'db'):
                self.db.close()
        finally:
            self.directory.cleanup()

    def games(self, slot):
        return Games(self, 'games:' + str(slot))

    def add_run(self, run):
        self.runs.append({k: v for k, v in run.items() if k != 'games'})

    def counter(self, name, common=False):
        return DiskCounter(self, name, common)

    def sequence(self, name, values):
        result = DiskList(self, name)
        for value in values:
            result.append(value)
        return result

    def group(self, row):
        self.db.execute('''INSERT INTO groups VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(key)
            DO UPDATE SET decisions=decisions+1,passes=passes+excluded.passes,
                assessments=assessments+excluded.assessments,censored=censored+excluded.censored''',
            (json.dumps((row['deckName'], row['deckSha256'], row['pilotMode'])),
             row['deckName'], row['deckSha256'], row['pilotMode'], 1,
             int(row['decision']['selection'] == 'PASS_PRIORITY'), int(bool(row['decision']['candidates'])),
             int(not row['outcomeEligible'])))

    def groups(self):
        for row in self.db.execute('SELECT deck,hash,pilot,decisions,passes,assessments,censored FROM groups ORDER BY deck,hash,pilot'):
            yield dict(zip(('deck', 'deckSha256', 'pilotMode', 'decisions', 'passes', 'withAssessments', 'censored'), row))


def materialize(value):
    """Library callers may explicitly request the aggregate report as ordinary Python values."""
    if isinstance(value, dict):
        return {key: materialize(item) for key, item in value.items()}
    if isinstance(value, list):
        return [materialize(item) for item in value]
    return value
