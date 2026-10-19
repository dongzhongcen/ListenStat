package com.dongzhongcen.listenstat

import com.dongzhongcen.listenstat.data.Migrations
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Instrumentation-free migration check on a real SQLite (sqlite-jdbc):
 * 1. create the v1 schema exactly as 0.1.x did (schemas/1.json) and insert rows,
 * 2. run Migrations.SQL_1_2,
 * 3. compare tables / columns / indices with a fresh DB built from schemas/2.json
 *    (the same checks Room's TableInfo validation performs) and verify the rows survived.
 */
class MigrationSqlTest {

    private val schemaDir = File("schemas/com.dongzhongcen.listenstat.data.AppDatabase")

    private fun create(conn: Connection, version: Int) {
        val db = JSONObject(File(schemaDir, "$version.json").readText()).getJSONObject("database")
        val entities = db.getJSONArray("entities")
        conn.createStatement().use { st ->
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                st.execute(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val idx = e.optJSONArray("indices")
                if (idx != null) for (j in 0 until idx.length()) {
                    st.execute(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = db.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) st.execute(setup.getString(i))
        }
    }

    private fun describe(conn: Connection): Map<String, Any> {
        val out = sortedMapOf<String, Any>()
        val tables = ArrayList<String>()
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' " +
                    "AND name != 'room_master_table' ORDER BY name"
            ).use { rs -> while (rs.next()) tables += rs.getString(1) }
            for (t in tables) {
                val cols = ArrayList<String>()
                st.executeQuery("PRAGMA table_info(`$t`)").use { rs ->
                    while (rs.next()) cols += listOf(
                        rs.getString("name"), rs.getString("type"), rs.getInt("notnull"),
                        rs.getInt("pk"), rs.getString("dflt_value")
                    ).joinToString("|")
                }
                out["table:$t"] = cols.sorted()
                val indices = ArrayList<String>()
                val names = ArrayList<Pair<String, Int>>()
                st.executeQuery("PRAGMA index_list(`$t`)").use { rs ->
                    while (rs.next()) if (rs.getString("origin") == "c") names += rs.getString("name") to rs.getInt("unique")
                }
                for ((n, unique) in names) {
                    val c = ArrayList<String>()
                    conn.createStatement().use { s2 ->
                        s2.executeQuery("PRAGMA index_info(`$n`)").use { rs -> while (rs.next()) c += rs.getString("name") }
                    }
                    indices += "$n|$unique|${c.joinToString(",")}"
                }
                out["indices:$t"] = indices.sorted()
            }
        }
        return out
    }

    @Test
    fun migrate1to2_matchesSchema2_andKeepsRows() {
        val migrated = DriverManager.getConnection("jdbc:sqlite::memory:")
        create(migrated, 1)
        migrated.createStatement().use { st ->
            st.execute(
                "INSERT INTO play_sessions (packageName, appLabel, title, artist, dayKey, startedAtMs, endedAtMs, durationMs) " +
                    "VALUES ('com.a', 'A', '第一章', 'X', '2026-10-01', 1000, 61000, 60000)," +
                    "('com.b', 'B', '', '', '2026-10-02', 2000, 3602000, 3600000)"
            )
        }
        Migrations.SQL_1_2.forEach { sql -> migrated.createStatement().use { it.execute(sql) } }

        val fresh = DriverManager.getConnection("jdbc:sqlite::memory:")
        create(fresh, 2)

        val expected = describe(fresh)
        assertEquals(4, (expected["indices:play_sessions"] as List<*>).size)
        assertEquals(2, (expected["table:achievements"] as List<*>).size)
        assertEquals(expected, describe(migrated))

        migrated.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*), SUM(durationMs) FROM play_sessions").use { rs ->
                assertTrue(rs.next())
                assertEquals(2, rs.getInt(1))
                assertEquals(3_660_000L, rs.getLong(2))
            }
            // new table usable
            st.execute("INSERT INTO achievements (id, unlockedAtMs) VALUES ('first_listen', 1)")
        }
    }

    @Test
    fun schema1_identityHash_isTheOneShippedIn_0_1_x() {
        val db = JSONObject(File(schemaDir, "1.json").readText()).getJSONObject("database")
        // hash found in the v0.1.1 APK's generated AppDatabase_Impl
        assertEquals("e15f60390fbcbc7e68c73feb6ed2e0bb", db.getString("identityHash"))
        assertEquals(1, db.getInt("version"))
    }
}
