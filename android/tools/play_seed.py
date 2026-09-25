#!/usr/bin/env python3
"""Builds a synthetic userdata.sqlite for Play Store screenshots.

Same schema as UserDataStore (user_version 2). All content is invented demo data — never a real
reader's library. Usage: play_seed.py <out.sqlite> [slide.jpg] [--locale de]

--locale writes the notes in that language (en, de, es, fr, it, ja, ko, pt-BR, zh-Hans); the two
sermon notes use the iOS demo library's own translations (DemoLibrary.swift, Localizable.xcstrings).
"""
import sqlite3, sys, os, uuid, datetime

args = sys.argv[1:]
locale = "en"
if "--locale" in args:
    i = args.index("--locale")
    locale = args[i + 1]
    del args[i:i + 2]
out = args[0]
slide = args[1] if len(args) > 1 else None
if os.path.exists(out):
    os.remove(out)
db = sqlite3.connect(out)
db.executescript("""
CREATE TABLE highlights (verse_key INTEGER NOT NULL, color TEXT NOT NULL, created_at INTEGER NOT NULL);
CREATE INDEX highlights_verse ON highlights(verse_key);
CREATE TABLE notes (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL DEFAULT '', body TEXT NOT NULL DEFAULT '',
  anchors TEXT NOT NULL DEFAULT '', first_verse_key INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL, origin TEXT NOT NULL DEFAULT 'manual');
CREATE TABLE favorites (id TEXT PRIMARY KEY NOT NULL, range TEXT NOT NULL, start_key INTEGER NOT NULL,
  end_key INTEGER NOT NULL, created_at INTEGER NOT NULL);
CREATE TABLE slide_photos (note_id TEXT PRIMARY KEY NOT NULL, jpeg BLOB NOT NULL);
PRAGMA user_version = 2;
""")

def ms(days_ago, hour=9):
    t = datetime.datetime(2026, 9, 20, hour, tzinfo=datetime.timezone.utc) - datetime.timedelta(days=days_ago)
    return int(t.timestamp() * 1000)

def k(b, c, v): return b * 1_000_000 + c * 1000 + v

hl = [((43, 10, 11), "yellow"), ((43, 10, 14), "green"), ((43, 10, 27), "blue"), ((43, 10, 28), "blue"),
      ((43, 3, 16), "yellow"), ((43, 3, 17), "yellow"), ((19, 23, 1), "green"), ((45, 8, 28), "pink"),
      ((43, 14, 6), "purple"), ((50, 4, 13), "blue"), ((23, 40, 31), "yellow"),
      ((20, 3, 5), "pink"), ((20, 3, 6), "pink"), ((25, 3, 22), "yellow"), ((25, 3, 23), "yellow"),
      ((40, 11, 28), "green"), ((40, 11, 29), "green"), ((40, 11, 30), "green")]
for i, ((b, c, v), col) in enumerate(hl):
    db.execute("INSERT INTO highlights VALUES (?,?,?)", (k(b, c, v), col, ms(10 - i % 7)))

# (title, body) per note and language: The Good Shepherd, No condemnation, Born of the Spirit, Contentment.
TEXT = {
    "en": [("The Good Shepherd",
            "• He knows his own by name (v. 14)\n• He lays down his life for the sheep (v. 11, 15)\n"
            "• One flock, one shepherd (v. 16)\n\nPsalm 23 answers John 10: the LORD who shepherds David is the shepherd who gives his life.\n\n"
            "Sunday Morning Series · Week 4"),
           ("Sunday sermon: No condemnation",
            "Life in the Spirit. Verse 1 is the hinge — everything after it flows from “no condemnation.”\n\n"
            "Adoption (v. 15): we cry “Abba, Father.”"),
           ("Evening sermon: Born of the Spirit",
            "• Nicodemus comes by night (v. 2)\n• “You must be born anew” — the Spirit’s work, not ours\n"
            "• The serpent in the wilderness points to the cross (Numbers 21:8–9)"),
           ("Contentment", "Paul learned it — it didn't come naturally. Strength for plenty and for want.")],
    "de": [("Der gute Hirte",
            "• Er kennt die Seinen mit Namen (V. 14)\n• Er lässt sein Leben für die Schafe (V. 11, 15)\n"
            "• Eine Herde, ein Hirte (V. 16)\n\nPsalm 23 antwortet auf Johannes 10: Der HERR, der David weidet, ist der Hirte, der sein Leben gibt.\n\n"
            "Predigtreihe am Sonntagmorgen · Woche 4"),
           ("Sonntagspredigt: Keine Verdammnis",
            "Leben im Geist. Vers 1 ist der Angelpunkt – alles danach folgt aus „keine Verdammnis“.\n\n"
            "Kindschaft (V. 15): Wir rufen „Abba, Vater!“"),
           ("Abendpredigt: Aus dem Geist geboren",
            "• Nikodemus kommt bei Nacht (V. 2)\n• „Ihr müsst von neuem geboren werden“ — das Werk des Geistes, nicht unseres\n"
            "• Die Schlange in der Wüste weist auf das Kreuz (4. Mose 21,8–9)"),
           ("Zufriedenheit", "Paulus hat es gelernt – es kam nicht von selbst. Kraft für Überfluss und für Mangel.")],
    "es": [("El buen pastor",
            "• Conoce a los suyos por su nombre (v. 14)\n• Da su vida por las ovejas (v. 11, 15)\n"
            "• Un rebaño, un pastor (v. 16)\n\nEl Salmo 23 responde a Juan 10: el SEÑOR que pastorea a David es el pastor que da su vida.\n\n"
            "Serie del domingo por la mañana · Semana 4"),
           ("Sermón del domingo: Ninguna condenación",
            "La vida en el Espíritu. El versículo 1 es la clave: todo lo que sigue nace de “ninguna condenación”.\n\n"
            "Adopción (v. 15): clamamos “¡Abba, Padre!”"),
           ("Sermón de la tarde: Nacido del Espíritu",
            "• Nicodemo viene de noche (v. 2)\n• «Tenéis que nacer de nuevo»: la obra del Espíritu, no la nuestra\n"
            "• La serpiente en el desierto apunta a la cruz (Números 21:8-9)"),
           ("Contentamiento", "Pablo lo aprendió; no le salió de forma natural. Fuerza para la abundancia y para la escasez.")],
    "fr": [("Le bon berger",
            "• Il connaît les siens par leur nom (v. 14)\n• Il donne sa vie pour ses brebis (v. 11, 15)\n"
            "• Un seul troupeau, un seul berger (v. 16)\n\nLe Psaume 23 répond à Jean 10 : l’Éternel, berger de David, est le berger qui donne sa vie.\n\n"
            "Série du dimanche matin · Semaine 4"),
           ("Sermon du dimanche : aucune condamnation",
            "La vie dans l’Esprit. Le verset 1 est la charnière — tout ce qui suit découle de « aucune condamnation ».\n\n"
            "L’adoption (v. 15) : nous crions « Abba ! Père ! »"),
           ("Prédication du soir : Né de l’Esprit",
            "• Nicodème vient de nuit (v. 2)\n• « Il vous faut naître de nouveau » — l’œuvre de l’Esprit, non la nôtre\n"
            "• Le serpent dans le désert annonce la croix (Nombres 21:8-9)"),
           ("Le contentement", "Paul l’a appris — cela ne lui est pas venu naturellement. De la force dans l’abondance comme dans le manque.")],
    "it": [("Il buon pastore",
            "• Conosce i suoi per nome (v. 14)\n• Dà la sua vita per le pecore (v. 11, 15)\n"
            "• Un solo gregge, un solo pastore (v. 16)\n\nIl Salmo 23 risponde a Giovanni 10: il SIGNORE che pasce Davide è il pastore che dà la sua vita.\n\n"
            "Serie della domenica mattina · Settimana 4"),
           ("Sermone domenicale: nessuna condanna",
            "La vita nello Spirito. Il versetto 1 è il cardine: tutto ciò che segue nasce da “nessuna condanna”.\n\n"
            "L’adozione (v. 15): gridiamo “Abbà, Padre!”"),
           ("Sermone serale: Nati dallo Spirito",
            "• Nicodemo viene di notte (v. 2)\n• «Dovete nascere di nuovo» — opera dello Spirito, non nostra\n"
            "• Il serpente nel deserto rimanda alla croce (Numeri 21:8–9)"),
           ("Contentezza", "Paolo l’ha imparato: non gli è venuto naturale. Forza nell’abbondanza e nella penuria.")],
    "ja": [("良い羊飼い",
            "• 自分の羊をその名で知っている（14節）\n• 羊のためにいのちを捨てる（11節、15節）\n"
            "• 一つの群れ、一人の牧者（16節）\n\n詩篇23篇はヨハネ10章に答える。ダビデを牧する主こそ、いのちを与える牧者である。\n\n"
            "日曜朝の連続説教・第4回"),
           ("日曜の説教：罪に定められることはない",
            "御霊による命。1節が要で、「罪に定められることはない」からすべてが流れ出す。\n\n"
            "子とされること（15節）：私たちは「アバ、父よ」と叫ぶ。"),
           ("夕拝の説教：霊によって生まれる",
            "• ニコデモが夜に訪ねてくる（2節）\n• 「人は新しく生まれなければならない」——私たちの業ではなく、御霊の業\n"
            "• 荒野の蛇は十字架を指し示す（民数記 21:8–9）"),
           ("満ち足りる心", "パウロはそれを学んだ。生まれつきではなかった。富むときにも乏しいときにも力がある。")],
    "ko": [("선한 목자",
            "• 자기 양의 이름을 안다 (14절)\n• 양을 위하여 목숨을 버린다 (11, 15절)\n"
            "• 한 무리, 한 목자 (16절)\n\n시편 23편은 요한복음 10장에 답한다: 다윗을 기르시는 여호와가 목숨을 주시는 목자이시다.\n\n"
            "주일 아침 연속 설교 · 4주차"),
           ("주일 설교: 정죄함이 없나니",
            "성령 안에서의 삶. 1절이 핵심입니다 — 그 뒤의 모든 내용이 “정죄함이 없나니”에서 흘러나옵니다.\n\n"
            "양자 됨 (15절): 우리가 “아빠 아버지”라고 부르짖는다."),
           ("저녁 설교: 성령으로 나다",
            "• 니고데모가 밤에 찾아옴 (2절)\n• “거듭나야 하리라” — 우리의 일이 아닌 성령의 일\n"
            "• 광야의 뱀이 십자가를 가리킴 (민수기 21:8–9)"),
           ("자족", "바울은 그것을 배웠다 — 저절로 된 것이 아니었다. 풍부할 때나 궁핍할 때나 힘을 주신다.")],
    "pt-BR": [("O bom pastor",
               "• Ele conhece os seus pelo nome (v. 14)\n• Ele dá a vida pelas ovelhas (v. 11, 15)\n"
               "• Um só rebanho, um só pastor (v. 16)\n\nO Salmo 23 responde a João 10: o SENHOR que pastoreia Davi é o pastor que dá a vida.\n\n"
               "Série de domingo de manhã · Semana 4"),
              ("Sermão de domingo: Nenhuma condenação",
               "A vida no Espírito. O versículo 1 é a chave — tudo depois dele flui de “nenhuma condenação”.\n\n"
               "Adoção (v. 15): clamamos “Aba, Pai!”"),
              ("Sermão da noite: Nascido do Espírito",
               "• Nicodemos vem de noite (v. 2)\n• “É necessário nascer de novo” — obra do Espírito, não nossa\n"
               "• A serpente no deserto aponta para a cruz (Números 21:8–9)"),
              ("Contentamento", "Paulo aprendeu isso — não veio naturalmente. Força na fartura e na necessidade.")],
    "zh-Hans": [("好牧人",
                 "• 他按着名叫自己的羊（第 14 节）\n• 他为羊舍命（第 11、15 节）\n"
                 "• 一群羊，一个牧人（第 16 节）\n\n诗篇 23 篇回应约翰福音 10 章：牧养大卫的耶和华，就是舍命的好牧人。\n\n"
                 "主日早晨系列讲道 · 第 4 周"),
                ("主日讲道：不再定罪",
                 "在圣灵里的生命。第 1 节是转折点——其后的一切都源于“不定罪”。\n\n"
                 "儿子的名分（第 15 节）：我们呼叫“阿爸，父”。"),
                ("晚堂讲道：从圣灵生的",
                 "• 尼哥底母夜间来访（第 2 节）\n• “人必须重生”——这是圣灵的工作，不是我们的\n"
                 "• 旷野中的铜蛇预表十字架（民数记 21:8–9）"),
                ("知足", "保罗是学会的——并非天生如此。无论富足或缺乏，都有力量。")],
}
if locale not in TEXT:
    sys.exit(f"no demo notes in {locale}: one of {', '.join(TEXT)}")
t = TEXT[locale]
notes = [
    (*t[0], [(k(43, 10, 11), k(43, 10, 15)), (k(19, 23, 1), k(19, 23, 6))], ms(0, 17), "camera"),
    (*t[1], [(k(45, 8, 1), k(45, 8, 17))], ms(6, 18), "manual"),
    (*t[2], [(k(43, 3, 1), k(43, 3, 21)), (k(4, 21, 4), k(4, 21, 9))], ms(13, 18), "manual"),
    (*t[3], [(k(50, 4, 10), k(50, 4, 13))], ms(20, 7), "manual"),
]
good_shepherd_id = None
for title, body, anchors, t, origin in notes:
    nid = str(uuid.uuid4())
    if good_shepherd_id is None:
        good_shepherd_id = nid
    anchors = sorted(anchors)
    db.execute("INSERT INTO notes VALUES (?,?,?,?,?,?,?,?)",
               (nid, title, body, ",".join(f"{a}-{b}" for a, b in anchors), anchors[0][0], t, t, origin))
if slide:
    db.execute("INSERT INTO slide_photos VALUES (?,?)", (good_shepherd_id, open(slide, "rb").read()))

favs = [(k(45, 8, 38), k(45, 8, 39)), (k(19, 23, 1), k(19, 23, 1)), (k(43, 14, 6), k(43, 14, 6)),
        (k(23, 40, 31), k(23, 40, 31)), (k(43, 10, 11), k(43, 10, 11))]
for i, (a, b) in enumerate(favs):
    db.execute("INSERT INTO favorites VALUES (?,?,?,?,?)", (str(uuid.uuid4()), f"{a}-{b}", a, b, ms(i + 1)))
db.commit()
db.execute("PRAGMA journal_mode=DELETE")
db.close()
print("wrote", out)
