package com.blainemiller.scripturealone.data.catalog

/**
 * Apple's answers to two locale questions, for every language code Apple's `Locale` knows (its
 * `Locale.LanguageCode.isoLanguageCodes`, plus the deprecated `iw`, `in`, `ji`, `mo`, `sh` that older
 * Android still reports, `tl` and `und`).
 *
 * - [alpha3] is `Locale.LanguageCode.identifier(.alpha3)`: "en" → "eng", "fil" → "fil", and nil for a
 *   code Apple doesn't know ("cmn", "xyz") — which Java's `getISO3Language` would pass through.
 * - [likelyScript] is the script of `Locale.Language(identifier: code).maximalIdentifier`, the
 *   likely-subtags answer: "en" → "Latn", "zh" → "Hans", "sr" → "Cyrl", "yue" → "Hant".
 *
 * Generated, not typed: a Swift script ran both questions over that list on macOS 27 and wrote these
 * strings. Regenerate the same way rather than editing an entry by hand, so the two apps keep agreeing.
 */
internal object LocaleTables {

    /** Two-letter (and deprecated) codes whose alpha-3 differs from the code itself, as `code=alpha3`. */
    private const val RENAMED =
        "aa=aar ab=abk ae=ave af=afr ak=aka am=amh an=arg ar=ara as=asm av=ava ay=aym az=aze " +
        "ba=bak be=bel bg=bul bi=bis bm=bam bn=ben bo=bod br=bre bs=bos ca=cat ce=che ch=cha " +
        "co=cos cr=cre cs=ces cu=chu cv=chv cy=cym da=dan de=deu dv=div dz=dzo ee=ewe el=ell " +
        "en=eng eo=epo es=spa et=est eu=eus fa=fas ff=ful fi=fin fj=fij fo=fao fr=fra fy=fry " +
        "ga=gle gd=gla gl=glg gn=grn gu=guj gv=glv ha=hau he=heb hi=hin ho=hmo hr=hrv ht=hat " +
        "hu=hun hy=hye hz=her ia=ina id=ind ie=ile ig=ibo ii=iii ik=ipk in=ind io=ido is=isl " +
        "it=ita iu=iku iw=heb ja=jpn ji=yid jv=jav ka=kat kg=kon ki=kik kj=kua kk=kaz kl=kal " +
        "km=khm kn=kan ko=kor kr=kau ks=kas ku=kur kv=kom kw=cor ky=kir la=lat lb=ltz lg=lug " +
        "li=lim ln=lin lo=lao lt=lit lu=lub lv=lav mg=mlg mh=mah mi=mri mk=mkd ml=mal mn=mon " +
        "mo=mol mr=mar ms=msa mt=mlt my=mya na=nau nb=nob nd=nde ne=nep ng=ndo nl=nld nn=nno " +
        "no=nor nr=nbl nv=nav ny=nya oc=oci oj=oji om=orm or=ori os=oss pa=pan pi=pli pl=pol " +
        "ps=pus pt=por qu=que rm=roh rn=run ro=ron ru=rus rw=kin sa=san sc=srd sd=snd se=sme " +
        "sg=sag sh=srp si=sin sk=slk sl=slv sm=smo sn=sna so=som sq=sqi sr=srp ss=ssw st=sot " +
        "su=sun sv=swe sw=swa ta=tam te=tel tg=tgk th=tha ti=tir tk=tuk tl=tgl tn=tsn to=ton " +
        "tr=tur ts=tso tt=tat tw=twi ty=tah ug=uig uk=ukr ur=urd uz=uzb ve=ven vi=vie vo=vol " +
        "wa=wln wo=wol xh=xho yi=yid yo=yor za=zha zh=zho zu=zul"

    /** Codes Apple knows whose alpha-3 is the code itself. */
    private const val UNCHANGED =
        "ace acf ach ada ady aeb afh agq ain akk akz ale aln alt ang anp apw arc arn aro arp " +
        "arq ars arw ary arz asa ase ast avk awa bal ban bar bas bax bbc bbj bej bem ber bew " +
        "bez bfd bfq bgc bgn bho bik bin bjn bkm bla blo bpy bqi bra brh brx bss bua bug bum " +
        "byn byv cad car cay cch ccp ceb cgg chb chg chk chm chn cho chp chr chy cic ckb com " +
        "cop cps crh crj crk crl crm csb cst csw cwd dak dar dav del den dgr din dje doi dsb " +
        "dtp dua dum dyo dyu dzg ebu efi egl egy eka elx enm esu ewo ext fan fat fil fit fla " +
        "fon frc frm fro frp frr frs fur gaa gag gan gay gba gbz gcf gez gil glk gmh goh gom " +
        "gon gor got grb grc gsw guc gur guz gwi hai hak haw hch hif hil hit hmn hsb hsn hup " +
        "iba ibb ilo inh isc izh jam jbo jgo jmc jpr jrb jut kaa kab kac kaj kam kaw kbd kbl " +
        "kcg kde kea ken kfo kgp kha kho khq khw kio kiu kkj kln kmb koi kok kos kpe krc kri " +
        "krj krl kru ksb ksf ksh kum kut kxv lad lag lah lam lez lfn lij liv lkt lmo lol loz " +
        "lrc ltg lua lui lun luo lus lut luy lzh lzz mad maf mag mai mak man mas mde mdf mdh " +
        "mdr men mer mfe mga mgh mgo mic mid min mis mnc mni moh mos mrj mua mul mus mwl mwr " +
        "mwv mye myv mzn nan nap naq nds new nez nia niu njo nmg nnh nnp nog non nov nqo nso " +
        "nus nwc nym nyn nyo nzi osa ota otk oui pag pal pam pap pau pcd pcm pdc pdt peo pfl " +
        "phn pms pnt pon pqm prg pro quc qug raj rap rar rej rgn rhg rif rof rom rtm rue rug " +
        "rup rwk sad sah sam saq sas sat saz sba sbp scn sco sdc sdh see seh sei sel ses sga " +
        "sgs shi shn shp shu sid sjd sje sju sli sly sma smj smn sms snk sog srn srr srs ssy " +
        "stq suk sus sux swb syc syr szl tcy tem teo ter tet tig tiv tkl tkr tlh tli tly tmh " +
        "tog tok tpi tru trv tsd tsi ttt tum tvl twq tyv tzm udm uga umb und vai vec vep vls " +
        "vmf vmw vot vro vun wae wal war was wbp wuu xal xmf xnr xog yao yap yav ybb yrl yue " +
        "zap zbl zea zen zgh zun zxx zza"

    /** `Script:code code code | Script:code …` */
    private const val SCRIPTS =
        "Arab:aeb ar arq ars ary arz bal bej bgn bqi brh chg ckb fa gbz glk khw lah lrc mde " +
        "mzn ota ps sd sdh shu swb ug | Aran:ks ur | Armi:arc | Armn:hy | Avst:ae | Bali:kaw " +
        "| Bamu:bax | Beng:as bn bpy mni | Brah:kho | Cakm:ccp | Cans:cr crj crk crl crm csw " +
        "cwd iu oj | Cher:chr | Copt:cop | Cyrl:ab ady alt av ba be bg bua ce chm crh cu cv " +
        "dar inh kaa kbd kk koi krc kum kv ky lez mdf mk mn mrj myv nog os ru rue sah sel sjd " +
        "sr tg tt tyv udm uk xal | Deva:anp awa bgc bho bra brx doi gon hi hif kok kru mag " +
        "mai mr mwr ne new raj sa xnr | Egyp:egy | Ethi:am byn gez ti tig wal | Geor:ka xmf | " +
        "Goth:got | Grek:el grc pnt tsd | Gujr:gu | Guru:pa | Hans:gan hak hsn nan wuu zh | " +
        "Hant:lzh yue | Hebr:he iw ji jpr jrb lad yi | Hmng:hmn | Jpan:ja | Kana:ain | " +
        "Khmr:km | Knda:kn tcy | Kore:ko | Laoo:lo | Latg:mga | Latn:aa ace acf ach ada af " +
        "afh agq ak akz ale aln an ang apw arn aro arp arw asa ast avk ay az ban bar bas bbc " +
        "bbj bem bew bez bfd bi bik bin bjn bkm bla blo bm br bs bss bug bum byv ca cad car " +
        "cay cch ceb cgg ch chb chk chn cho chp chy cic co com cps cs csb cst cy da dak dav " +
        "de del den dgr din dje dsb dtp dua dum dyo dyu dzg ebu ee efi egl eka en enm eo es " +
        "esu et eu ewo ext fan ff fi fil fit fj fla fo fon fr frc frm fro frp frr frs fur fy " +
        "ga gaa gag gay gba gcf gd gil gl gmh gn goh gor grb gsw guc gur guz gv gwi ha hai " +
        "haw hch hil ho hr hsb ht hu hup hz ia iba ibb id ie ig ik ilo in io is isc it izh " +
        "jam jbo jgo jmc jut jv kab kac kaj kam kbl kcg kde kea ken kfo kg kgp kha khq ki kio " +
        "kiu kj kkj kl kln kmb kos kpe kr kri krj krl ksb ksf ksh ku kut kw kxv la lag lam lb " +
        "lfn lg li lij liv lkt lmo ln lol loz lt ltg lu lua lui lun luo lus lut luy lv lzz " +
        "mad maf mak man mas mdh mdr men mer mfe mg mgh mgo mh mi mic min mo moh mos ms mt " +
        "mua mus mwl mwv mye na nap naq nb nd nds nez ng nia niu njo nl nmg nn nnh no nov nr " +
        "nso nus nv ny nym nyn nyo nzi oc om pag pam pap pau pcd pcm pdc pdt pfl pi pl pms " +
        "pon pqm prg pro pt qu quc qug rap rar rej rgn rif rm rn ro rof rom rtm rug rup rw " +
        "rwk sad saq sas sba sbp sc scn sco sdc se see seh sei ses sg sga sgs shp sid sje sju " +
        "sk sl sli sly sm sma smj smn sms sn snk so sq srn srr srs ss ssy st stq su suk sus " +
        "sv sw szl tem teo ter tet tiv tk tkl tkr tl tli tly tmh tn to tog tok tpi tr tru trv " +
        "ts tsi ttt tum tvl twq ty tzm umb und uz ve vec vep vi vls vmf vmw vo vot vro vun wa " +
        "wae war was wbp wo xh xog yao yap yav ybb yo yrl za zap zea zu zun zza | Mand:mid | " +
        "Mlym:ml | Mong:mnc | Mymr:my shn | Newa:nwc | Nkoo:nqo | Olck:sat | Orkh:otk | " +
        "Orya:or | Osge:osa | Ougr:oui | Phli:pal | Phnx:phn | Rohg:rhg | Runr:non | Samr:sam " +
        "| Saur:saz | Sgnw:ase | Sinh:si | Sogd:sog | Syrc:syc syr | Taml:bfq ta | Telu:te | " +
        "Tfng:ber shi zen zgh | Thaa:dv | Thai:th | Tibt:bo dz | Ugar:uga | Vaii:vai | " +
        "Wcho:nnp | Xpeo:peo | Xsux:akk hit | Yiii:ii"

    private val alpha3Table: Map<String, String> by lazy {
        val table = HashMap<String, String>()
        for (pair in RENAMED.split(' ')) {
            val (code, alpha3) = pair.split('=')
            table[code] = alpha3
        }
        for (code in UNCHANGED.split(' ')) table[code] = code
        table
    }

    private val scriptTable: Map<String, String> by lazy {
        val table = HashMap<String, String>()
        for (group in SCRIPTS.split('|')) {
            val (script, codes) = group.trim().split(':')
            for (code in codes.split(' ')) if (code.isNotEmpty()) table[code] = script
        }
        table
    }

    /** `Locale.LanguageCode(code).identifier(.alpha3)`; [code] is a lowercased language subtag. */
    fun alpha3(code: String): String? = alpha3Table[code]

    /** The script of `Locale.Language(identifier: code).maximalIdentifier`, or null. */
    fun likelyScript(code: String): String? = scriptTable[code]
}
