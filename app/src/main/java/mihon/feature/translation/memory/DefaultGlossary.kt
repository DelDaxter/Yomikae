package mihon.feature.translation.memory

/**
 * Yomikae: starting point of the global glossary (Korean → English), the terms every
 * manhwa reader meets: genre vocabulary (regression, hunters, gates, dungeons), ranks of
 * nobility, martial-arts words, forms of address. One "source = translation" per line, the
 * same format as a series glossary. Only the entries whose source appears on a page are sent
 * to the model, so the list can grow without slowing the phone down.
 *
 * No open glossary of this kind exists online in a reusable form (the wiki glossaries are
 * general and under share-alike licences), so this list was written for Yomikae; the
 * translations follow the usual choices of official English webtoons.
 */
object DefaultGlossary {

    val KO_EN: String = """
        # Genre : régression, éveil, chasseurs
        회귀 = regression
        회귀자 = regressor
        회귀했다 = regressed
        환생 = reincarnation
        빙의 = possession
        각성 = awakening
        각성자 = awakened
        헌터 = hunter
        게이트 = gate
        던전 = dungeon
        몬스터 = monster
        마수 = magic beast
        마물 = demonic creature
        마족 = demon
        마왕 = Demon King
        마법 = magic
        마법사 = mage
        마나 = mana
        마력 = magic power
        마탑 = Magic Tower
        스킬 = skill
        레벨 = level
        상태창 = status window
        시스템 = system
        퀘스트 = quest
        스탯 = stats
        랭커 = ranker
        길드 = guild
        길드장 = guild master
        협회 = Association
        이세계 = another world
        소환 = summoning
        용사 = hero
        아카데미 = academy
        # Noblesse et cour
        황제 = Emperor
        황후 = Empress
        황태자 = Crown Prince
        황녀 = Princess
        왕국 = kingdom
        제국 = empire
        대공 = Grand Duke
        공작 = Duke
        공작가 = ducal house
        공작님 = Your Grace
        후작 = Marquis
        백작 = Count
        자작 = Viscount
        남작 = Baron
        귀족 = noble
        평민 = commoner
        기사 = knight
        기사단 = knight order
        도련님 = young master
        아가씨 = young lady
        영애 = young lady
        공녀 = duke's daughter
        공자 = young lord
        후계자 = heir
        집사 = butler
        시녀 = maid
        하녀 = maid
        전하 = Your Highness
        폐하 = Your Majesty
        각하 = Your Excellency
        성녀 = Saintess
        성기사 = paladin
        신전 = temple
        용병 = mercenary
        암살자 = assassin
        # Arts martiaux (murim)
        무공 = martial arts
        내공 = inner energy
        무림 = murim
        강호 = martial world
        문파 = sect
        장로 = elder
        사부 = master
        제자 = disciple
        검사 = swordsman
        검술 = swordsmanship
        마검 = demon sword
        성검 = holy sword
        # Fantastique
        드래곤 = dragon
        엘프 = elf
        드워프 = dwarf
        수인 = beastkin
        계약 = contract
        저주 = curse
        축복 = blessing
        폭주 = rampage
    """.trimIndent()
}
