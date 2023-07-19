import me.fan87.gitbuster.GitBuster
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.fusesource.jansi.Ansi
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Scanner
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

const val REPO_DIR = "/tmp/graph-gen/"

object GraphCreator {
    @JvmStatic
    fun main(vararg args: String) {
        val years = intArrayOf(/*2018, 2019, 2020, 2021, 2022, 2023, */2024)

        for (year in years) {
            GitBuster(Git.open(File(REPO_DIR))).let { it.open(); write(it, year, "$year.png", 0..25); it.push() }
            println("Finished first half of $year")
            Scanner(System.`in`).nextLine()
            GitBuster(Git.open(File(REPO_DIR))).let { it.open(); write(it, year, "$year.png", 26..50); it.push() }
            println("Finished second half of $year")
            Scanner(System.`in`).nextLine()
        }


    }

    fun write(buster: GitBuster, year: Int, imagePath: String, part: IntRange) {


        val day = LocalDate.of(year, 1, 1)
        val startDate = LocalDate.of(year, 1, 8 - day.dayOfWeek.value % 7)
        val image = ImageIO.read(javaClass.classLoader.getResourceAsStream(imagePath))
        val baseTime = startDate.atTime(12, 0, 0).atZone(ZoneId.of("+00:00")).toInstant().toEpochMilli()


        for (x in part) {
            for (y in 0..<7) {
                val level = ((image.getRGB(x, y) and 0x000000ff) / 255.0) * 5
                repeat (5 - level.toInt()) {
                    buster.createCommit {
                        message = "Pixel Pos: $x/$y @ $year  graph  $imagePath"
                        author = PersonIdent("opportunity-lib", "fan87.tmp.02@gmail.com", (x*7 + y)* TimeUnit.DAYS.toMillis(1) + baseTime, 0)
                        committer = author
                    }
                }
            }
        }
    }
}
