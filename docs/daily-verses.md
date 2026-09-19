# Verse of the Day

The Verse of the Day widget, the Apple Watch app and its complications draw from one
hand-curated list of **365 passages** — one for every day of the year. This page
lists every choice so it can be reviewed; the source of truth is
[`Data/daily-verses.tsv`](../Data/daily-verses.tsv), and
`python3 Tools/build_companion_data.py` regenerates this page along with the text the
widgets bundle.

## How the passages were chosen

- **Beloved and well known.** Verses Christians have long memorized, sung and quoted:
  the Shema, the Aaronic blessing, Psalm 23, Isaiah 53, John 3:16, Romans 8, the
  fruit of the Spirit, the Great Commission. Topical memory plans and verse-of-the-day
  traditions overlap heavily on these passages, and this list leans on that common core
  rather than on any one publisher’s plan.
- **The whole canon.** Every section of the Bible is represented — Law, History, Wisdom,
  the Major and Minor Prophets, the Gospels, Acts, Paul’s letters, the General Letters
  and Revelation — not only the Psalms and the New Testament.
- **Readable on a wrist.** Each passage is one to three verses that make sense on their
  own, so a small widget or a watch complication is not a fragment of an argument.
- **Translation-neutral.** Every passage exists in all bundled translations (ASV, BSB,
  KJV); the build fails otherwise. Verses the ASV relegates to footnotes are avoided.

## How a day picks its passage

The day’s passage depends only on the local calendar date, so every device shows the same
verse on the same day, offline. `DailyVerseCatalog` counts days since 1 January 2000 and
steps through the list by a fixed stride that is coprime with its length, so each passage
appears exactly once per cycle and consecutive days jump across the canon instead of
walking through Genesis in January. Widgets change at local midnight.

## The list

### Law (25)

| Passage | Theme |
|---|---|
| Genesis 1:1 | Creation |
| Genesis 1:27 | The image of God |
| Genesis 1:31 | Very good |
| Genesis 3:15 | The first promise of a Savior |
| Genesis 12:2–3 | A blessing to all nations |
| Genesis 15:6 | Faith counted as righteousness |
| Genesis 18:14 | Nothing too hard for the LORD |
| Genesis 22:8 | God will provide |
| Genesis 28:15 | I am with you |
| Genesis 50:20 | God meant it for good |
| Exodus 3:14 | I AM WHO I AM |
| Exodus 14:14 | The LORD will fight for you |
| Exodus 15:2 | My strength and my song |
| Exodus 33:14 | My presence will go with you |
| Exodus 34:6 | Merciful and gracious |
| Leviticus 19:18 | Love your neighbor |
| Numbers 6:24–26 | The priestly blessing |
| Numbers 23:19 | God does not lie |
| Deuteronomy 6:4–5 | The Shema |
| Deuteronomy 7:9 | The faithful God |
| Deuteronomy 8:3 | Not by bread alone |
| Deuteronomy 29:29 | The secret things |
| Deuteronomy 31:6 | Be strong and courageous |
| Deuteronomy 31:8 | He will not forsake you |
| Deuteronomy 33:27 | The everlasting arms |

### History (18)

| Passage | Theme |
|---|---|
| Joshua 1:8 | Meditate day and night |
| Joshua 1:9 | Do not be afraid |
| Joshua 24:15 | As for me and my house |
| Ruth 1:16 | Your God my God |
| 1 Samuel 2:2 | None holy like the LORD |
| 1 Samuel 15:22 | To obey is better than sacrifice |
| 1 Samuel 16:7 | The LORD looks on the heart |
| 2 Samuel 22:31 | His way is perfect |
| 1 Kings 19:12 | A still small voice |
| 2 Kings 6:16 | More are with us |
| 1 Chronicles 16:11 | Seek His face |
| 1 Chronicles 16:34 | His mercy endures forever |
| 1 Chronicles 29:11 | Yours is the greatness |
| 2 Chronicles 7:14 | If My people humble themselves |
| 2 Chronicles 16:9 | The eyes of the LORD |
| 2 Chronicles 20:15 | The battle is God’s |
| Nehemiah 8:10 | The joy of the LORD |
| Esther 4:14 | For such a time as this |

### Wisdom & Poetry (78)

| Passage | Theme |
|---|---|
| Job 1:21 | Blessed be the name of the LORD |
| Job 19:25 | My Redeemer lives |
| Job 23:10 | Refined as gold |
| Job 42:2 | You can do all things |
| Psalm 1:1–2 | The blessed man |
| Psalm 4:8 | Sleep in peace |
| Psalm 8:3–4 | What is man? |
| Psalm 16:8 | I shall not be moved |
| Psalm 16:11 | Fullness of joy |
| Psalm 18:2 | My rock and fortress |
| Psalm 19:1 | The heavens declare |
| Psalm 19:14 | Words of my mouth |
| Psalm 23:1 | The LORD is my shepherd |
| Psalm 23:4 | The valley of the shadow |
| Psalm 23:6 | Goodness and mercy |
| Psalm 25:4–5 | Teach me Your paths |
| Psalm 27:1 | Whom shall I fear? |
| Psalm 27:14 | Wait for the LORD |
| Psalm 28:7 | My strength and shield |
| Psalm 30:5 | Joy comes in the morning |
| Psalm 32:1 | Sin forgiven |
| Psalm 32:8 | I will instruct you |
| Psalm 34:8 | Taste and see |
| Psalm 34:18 | Near to the brokenhearted |
| Psalm 37:4 | Delight in the LORD |
| Psalm 37:5 | Commit your way |
| Psalm 42:1 | As the deer pants |
| Psalm 42:11 | Hope in God |
| Psalm 46:1 | A very present help |
| Psalm 46:10 | Be still |
| Psalm 51:10 | A clean heart |
| Psalm 55:22 | Cast your burden |
| Psalm 56:3 | When I am afraid |
| Psalm 62:8 | Pour out your heart |
| Psalm 63:1 | My soul thirsts for You |
| Psalm 73:26 | The strength of my heart |
| Psalm 84:11 | A sun and shield |
| Psalm 90:2 | From everlasting to everlasting |
| Psalm 90:12 | Number our days |
| Psalm 91:1–2 | Under His shadow |
| Psalm 91:11 | His angels guard you |
| Psalm 95:6 | Come, let us worship |
| Psalm 100:4 | Enter His gates |
| Psalm 100:5 | The LORD is good |
| Psalm 103:1–2 | Bless the LORD, O my soul |
| Psalm 103:12 | As far as east from west |
| Psalm 107:1 | His love endures |
| Psalm 118:24 | This is the day |
| Psalm 119:11 | Hidden in my heart |
| Psalm 119:105 | A lamp to my feet |
| Psalm 121:1–2 | My help comes from the LORD |
| Psalm 121:7–8 | Your going out and coming in |
| Psalm 127:1 | Unless the LORD builds |
| Psalm 136:1 | Give thanks |
| Psalm 139:14 | Fearfully and wonderfully made |
| Psalm 139:23–24 | Search me, O God |
| Psalm 143:8 | Your lovingkindness in the morning |
| Psalm 145:18 | Near to all who call |
| Psalm 147:3 | He heals the brokenhearted |
| Psalm 150:6 | Let everything praise |
| Proverbs 1:7 | The beginning of knowledge |
| Proverbs 3:5–6 | Trust in the LORD |
| Proverbs 4:23 | Guard your heart |
| Proverbs 9:10 | The beginning of wisdom |
| Proverbs 15:1 | A gentle answer |
| Proverbs 16:3 | Commit your works |
| Proverbs 16:9 | The LORD directs his steps |
| Proverbs 17:17 | A friend loves at all times |
| Proverbs 18:10 | A strong tower |
| Proverbs 19:21 | The LORD’s purpose stands |
| Proverbs 22:6 | Train up a child |
| Proverbs 27:17 | Iron sharpens iron |
| Proverbs 31:30 | A woman who fears the LORD |
| Ecclesiastes 3:1 | A time for everything |
| Ecclesiastes 3:11 | Eternity in their hearts |
| Ecclesiastes 12:1 | Remember your Creator |
| Ecclesiastes 12:13 | The whole duty of man |
| Song of Solomon 8:7 | Many waters cannot quench love |

### Prophets (48)

| Passage | Theme |
|---|---|
| Isaiah 1:18 | White as snow |
| Isaiah 6:3 | Holy, holy, holy |
| Isaiah 6:8 | Here am I; send me |
| Isaiah 7:14 | Immanuel |
| Isaiah 9:6 | Unto us a child is born |
| Isaiah 12:2 | God is my salvation |
| Isaiah 26:3 | Perfect peace |
| Isaiah 40:8 | The word stands forever |
| Isaiah 40:29 | Power to the faint |
| Isaiah 40:31 | Wings like eagles |
| Isaiah 41:10 | Fear not, I am with you |
| Isaiah 43:1 | You are Mine |
| Isaiah 43:2 | Through the waters |
| Isaiah 43:19 | A new thing |
| Isaiah 49:16 | Engraved on His palms |
| Isaiah 52:7 | Beautiful feet |
| Isaiah 53:5 | By His stripes |
| Isaiah 53:6 | All we like sheep |
| Isaiah 55:6 | Seek the LORD while He may be found |
| Isaiah 55:8–9 | His thoughts are higher |
| Isaiah 55:11 | My word will not return empty |
| Isaiah 58:11 | A watered garden |
| Isaiah 61:1 | Good news to the poor |
| Isaiah 64:8 | The potter and the clay |
| Jeremiah 17:7 | Blessed is the one who trusts |
| Jeremiah 29:11 | Plans for a future and a hope |
| Jeremiah 29:13 | Seek Me with all your heart |
| Jeremiah 31:3 | An everlasting love |
| Jeremiah 33:3 | Call to Me |
| Lamentations 3:22–23 | New every morning |
| Lamentations 3:25 | Good to those who wait |
| Ezekiel 36:26 | A new heart |
| Daniel 3:17 | Our God is able |
| Hosea 6:6 | Mercy, not sacrifice |
| Joel 2:13 | Rend your heart |
| Joel 2:32 | Whoever calls on the name |
| Amos 5:24 | Let justice roll |
| Jonah 2:9 | Salvation is of the LORD |
| Micah 6:8 | Do justice, love mercy, walk humbly |
| Micah 7:18 | He delights in mercy |
| Nahum 1:7 | A stronghold in trouble |
| Habakkuk 2:4 | The just shall live by faith |
| Habakkuk 3:18 | Yet I will rejoice |
| Zephaniah 3:17 | He rejoices over you with singing |
| Zechariah 4:6 | Not by might, but by My Spirit |
| Zechariah 9:9 | Your King comes |
| Malachi 3:6 | I do not change |
| Malachi 4:2 | The Sun of righteousness |

### Gospels & Acts (71)

| Passage | Theme |
|---|---|
| Matthew 1:21 | He will save His people |
| Matthew 4:19 | Fishers of men |
| Matthew 5:3 | Poor in spirit |
| Matthew 5:8 | The pure in heart |
| Matthew 5:9 | The peacemakers |
| Matthew 5:14 | The light of the world |
| Matthew 5:16 | Let your light shine |
| Matthew 5:44 | Love your enemies |
| Matthew 6:9–10 | The Lord’s Prayer |
| Matthew 6:21 | Where your treasure is |
| Matthew 6:33 | Seek first the kingdom |
| Matthew 6:34 | Do not worry about tomorrow |
| Matthew 7:7 | Ask, seek, knock |
| Matthew 7:12 | The golden rule |
| Matthew 11:28 | Come to Me and rest |
| Matthew 11:29 | Gentle and lowly in heart |
| Matthew 16:24 | Take up your cross |
| Matthew 18:20 | Where two or three gather |
| Matthew 19:26 | All things are possible with God |
| Matthew 22:37–39 | The great commandments |
| Matthew 24:35 | My words will not pass away |
| Matthew 28:19–20 | The Great Commission |
| Mark 1:15 | Repent and believe |
| Mark 9:24 | Help my unbelief |
| Mark 10:45 | To serve and to give His life |
| Mark 16:15 | Preach the gospel |
| Luke 1:37 | Nothing impossible with God |
| Luke 1:46–47 | My soul magnifies the Lord |
| Luke 2:10–11 | Good news of great joy |
| Luke 2:14 | Glory to God in the highest |
| Luke 6:31 | Do to others |
| Luke 6:38 | Give, and it will be given |
| Luke 9:23 | Deny yourself daily |
| Luke 12:32 | Fear not, little flock |
| Luke 19:10 | To seek and to save the lost |
| Luke 23:34 | Father, forgive them |
| Luke 24:6 | He is risen |
| John 1:1 | In the beginning was the Word |
| John 1:12 | Children of God |
| John 1:14 | The Word became flesh |
| John 1:29 | The Lamb of God |
| John 3:3 | Born again |
| John 3:16 | God so loved the world |
| John 3:17 | Not to condemn but to save |
| John 4:24 | Spirit and truth |
| John 5:24 | From death to life |
| John 6:35 | The bread of life |
| John 6:37 | I will never cast out |
| John 8:12 | The light of the world |
| John 8:32 | The truth will set you free |
| John 8:36 | Free indeed |
| John 10:10 | Life abundantly |
| John 10:11 | The good shepherd |
| John 10:27–28 | My sheep hear My voice |
| John 11:25–26 | The resurrection and the life |
| John 13:34–35 | Love one another |
| John 14:1 | Let not your heart be troubled |
| John 14:6 | The way, the truth and the life |
| John 14:27 | My peace I give you |
| John 15:5 | The vine and the branches |
| John 15:13 | Greater love has no one |
| John 16:33 | I have overcome the world |
| John 17:3 | This is eternal life |
| John 17:17 | Your word is truth |
| John 20:31 | That you may believe |
| Acts 1:8 | You will be My witnesses |
| Acts 2:38 | Repent and be baptized |
| Acts 4:12 | No other name |
| Acts 16:31 | Believe and be saved |
| Acts 17:28 | In Him we live |
| Acts 20:24 | Finish the race |

### Paul’s Letters (85)

| Passage | Theme |
|---|---|
| Romans 1:16 | Not ashamed of the gospel |
| Romans 1:17 | The righteous shall live by faith |
| Romans 3:23 | All have sinned |
| Romans 3:24 | Justified freely by grace |
| Romans 5:1 | Peace with God |
| Romans 5:8 | While we were still sinners |
| Romans 6:23 | The gift of God |
| Romans 8:1 | No condemnation |
| Romans 8:18 | Glory to be revealed |
| Romans 8:26 | The Spirit helps our weakness |
| Romans 8:28 | All things work together for good |
| Romans 8:31 | If God is for us |
| Romans 8:38–39 | Nothing can separate us |
| Romans 10:9 | Confess and believe |
| Romans 10:17 | Faith comes by hearing |
| Romans 11:33 | The depth of the riches |
| Romans 12:1 | A living sacrifice |
| Romans 12:2 | Be transformed |
| Romans 12:12 | Rejoicing in hope |
| Romans 12:21 | Overcome evil with good |
| Romans 13:10 | Love fulfills the law |
| Romans 15:4 | Written for our learning |
| Romans 15:13 | The God of hope |
| 1 Corinthians 1:18 | The word of the cross |
| 1 Corinthians 2:9 | Eye has not seen |
| 1 Corinthians 6:19–20 | Bought with a price |
| 1 Corinthians 10:13 | A way of escape |
| 1 Corinthians 10:31 | Do all to the glory of God |
| 1 Corinthians 13:4 | Love is patient |
| 1 Corinthians 13:13 | The greatest of these |
| 1 Corinthians 15:3–4 | Christ died and rose |
| 1 Corinthians 15:57 | Thanks be to God |
| 1 Corinthians 15:58 | Your labor is not in vain |
| 2 Corinthians 1:3 | The God of all comfort |
| 2 Corinthians 4:18 | The things unseen |
| 2 Corinthians 5:7 | By faith, not by sight |
| 2 Corinthians 5:17 | A new creation |
| 2 Corinthians 5:21 | He became sin for us |
| 2 Corinthians 9:7 | A cheerful giver |
| 2 Corinthians 12:9 | My grace is sufficient |
| Galatians 2:20 | Crucified with Christ |
| Galatians 5:1 | Stand fast in freedom |
| Galatians 5:22–23 | The fruit of the Spirit |
| Galatians 6:2 | Bear one another’s burdens |
| Galatians 6:9 | Do not grow weary |
| Ephesians 1:7 | Redemption through His blood |
| Ephesians 2:8–9 | Saved by grace through faith |
| Ephesians 2:10 | His workmanship |
| Ephesians 3:20 | Exceedingly abundantly |
| Ephesians 4:29 | Words that build up |
| Ephesians 4:32 | Kind and forgiving |
| Ephesians 5:2 | Walk in love |
| Ephesians 6:10 | Strong in the Lord |
| Ephesians 6:11 | The whole armor of God |
| Philippians 1:6 | He who began a good work |
| Philippians 1:21 | To live is Christ |
| Philippians 2:3 | Humility |
| Philippians 2:10–11 | Every knee shall bow |
| Philippians 3:14 | The upward call |
| Philippians 4:4 | Rejoice in the Lord always |
| Philippians 4:6–7 | The peace of God |
| Philippians 4:8 | Whatever is true |
| Philippians 4:13 | I can do all things |
| Philippians 4:19 | God will supply every need |
| Colossians 1:17 | In Him all things hold together |
| Colossians 3:2 | Set your mind on things above |
| Colossians 3:15 | Let peace rule |
| Colossians 3:23 | Work heartily, as for the Lord |
| 1 Thessalonians 5:11 | Encourage one another |
| 1 Thessalonians 5:16–18 | Rejoice, pray, give thanks |
| 1 Thessalonians 5:24 | He who calls you is faithful |
| 2 Thessalonians 3:3 | The Lord is faithful |
| 2 Thessalonians 3:16 | The Lord of peace |
| 1 Timothy 1:15 | Christ came to save sinners |
| 1 Timothy 2:5 | One mediator |
| 1 Timothy 4:12 | An example to believers |
| 1 Timothy 6:6 | Godliness with contentment |
| 1 Timothy 6:12 | Fight the good fight |
| 2 Timothy 1:7 | Power, love and a sound mind |
| 2 Timothy 2:15 | Rightly dividing the word |
| 2 Timothy 3:16–17 | All Scripture is God-breathed |
| 2 Timothy 4:7 | I have kept the faith |
| Titus 2:11 | Grace has appeared |
| Titus 3:5 | Not by works of righteousness |
| Philemon 1:7 | Hearts refreshed |

### General Letters & Revelation (40)

| Passage | Theme |
|---|---|
| Hebrews 4:12 | The word is living and active |
| Hebrews 4:16 | The throne of grace |
| Hebrews 10:23 | He who promised is faithful |
| Hebrews 10:24 | Stir up love and good works |
| Hebrews 11:1 | Faith defined |
| Hebrews 11:6 | Without faith |
| Hebrews 12:2 | Looking unto Jesus |
| Hebrews 13:5 | I will never leave you |
| Hebrews 13:8 | The same yesterday, today and forever |
| James 1:2–3 | Count it all joy |
| James 1:5 | Ask for wisdom |
| James 1:17 | Every good gift |
| James 1:22 | Doers of the word |
| James 4:8 | Draw near to God |
| James 4:10 | Humble yourselves |
| James 5:16 | The prayer of the righteous |
| 1 Peter 1:3 | A living hope |
| 1 Peter 2:9 | A chosen people |
| 1 Peter 2:24 | By His wounds you were healed |
| 1 Peter 3:15 | Be ready to give an answer |
| 1 Peter 5:7 | Cast all your cares |
| 1 Peter 5:10 | The God of all grace |
| 2 Peter 1:3 | All things for life and godliness |
| 2 Peter 3:9 | Not willing that any perish |
| 2 Peter 3:18 | Grow in grace |
| 1 John 1:9 | If we confess our sins |
| 1 John 3:1 | What manner of love |
| 1 John 4:4 | Greater is He who is in you |
| 1 John 4:8 | God is love |
| 1 John 4:18 | Perfect love casts out fear |
| 1 John 4:19 | We love because He first loved us |
| 1 John 5:14 | Confidence in prayer |
| 3 John 1:4 | No greater joy |
| Jude 1:24–25 | Able to keep you from stumbling |
| Revelation 1:8 | The Alpha and the Omega |
| Revelation 3:20 | I stand at the door and knock |
| Revelation 4:11 | Worthy are You |
| Revelation 21:4 | No more tears |
| Revelation 21:5 | All things new |
| Revelation 22:13 | The First and the Last |
