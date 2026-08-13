package io.github.bmcsilva.latente.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A geometria do visor.
 *
 * Orientação e enquadramento saem errados em silêncio: uma imagem ao contrário vê-se, mas uma imagem
 * ligeiramente esticada ou cortada nas bordas não — e cortada é a pior, porque esconderia parte do que
 * vai ser gravado. É por isso que esta conta está em Kotlin e não no shader.
 */
class PresentTest {

    /** O sensor deste telefone: 4080×3060, e o visor agrupa para 2040×1530. */
    private val largura = 2040
    private val altura = 1530

    /** Ecrã do dispositivo de referência, em retrato. */
    private val ecraLargura = 1080
    private val ecraAltura = 2196

    // -----------------------------------------------------------------------------------------
    // Rotação
    // -----------------------------------------------------------------------------------------

    /**
     * O caso real: sensor a 90°, telefone em retrato. A imagem tem de rodar 90°.
     */
    @Test
    fun theReferenceDeviceInPortraitNeedsNinetyDegrees() {
        assertEquals(90, Present.rotationFor(90, 0))
    }

    /** Rodando o telefone para o lado, o sensor já está de pé e não se roda nada. */
    @Test
    fun turningThePhoneSidewaysCancelsTheSensorOrientation() {
        assertEquals(0, Present.rotationFor(90, 90))
        assertEquals(180, Present.rotationFor(90, 270))
    }

    /** Nunca sai um ângulo negativo, que o shader não saberia interpretar. */
    @Test
    fun theAngleIsAlwaysBetweenZeroAndThreeHundredSixty() {
        for (sensor in intArrayOf(0, 90, 180, 270)) {
            for (ecra in intArrayOf(0, 90, 180, 270)) {
                val r = Present.rotationFor(sensor, ecra)
                assertTrue("sensor $sensor ecrã $ecra deu $r", r >= 0 && r < 360)
                assertEquals("devia ser múltiplo de 90", 0, r % 90)
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // A etiqueta de orientação do ficheiro
    // -----------------------------------------------------------------------------------------

    /**
     * O caso que estava errado até agora: escrevia-se sempre `1`, «normal», e **todas** as fotografias
     * tiradas em retrato saíam viradas de lado em qualquer visualizador. O mosaico é entregue na
     * orientação do sensor, que é deitada, e é a etiqueta que diz ao visualizador como a pôr de pé.
     */
    @Test
    fun ninetyDegreesTagsAsRotateNinety() {
        assertEquals(6, Present.exifOrientation(90))
    }

    @Test
    fun theTagFollowsTheTiffSpecification() {
        assertEquals("já de pé", 1, Present.exifOrientation(0))
        assertEquals("meia volta", 3, Present.exifOrientation(180))
        assertEquals("90° no sentido inverso", 8, Present.exifOrientation(270))
    }

    /** Ângulos fora da volta ou negativos não podem dar uma etiqueta inválida. */
    @Test
    fun anglesOutsideTheTurnStillGiveAValidTag() {
        assertEquals(6, Present.exifOrientation(450))
        assertEquals(8, Present.exifOrientation(-90))
        assertEquals(1, Present.exifOrientation(720))
        for (g in intArrayOf(-360, -270, -180, -90, 0, 90, 180, 270, 360, 450)) {
            assertTrue("$g deu " + Present.exifOrientation(g),
                Present.exifOrientation(g) in intArrayOf(1, 3, 6, 8))
        }
    }

    /**
     * A etiqueta tem de acompanhar a **mesma** rotação que o visor usa para desenhar. Se divergissem, o
     * visor mostrava direito e o ficheiro saía deitado — que é exactamente o defeito que havia.
     */
    @Test
    fun theTagUsesTheSameRotationTheViewfinderDraws() {
        val sensor = 90
        for (ecra in intArrayOf(0, 90, 180, 270)) {
            val r = Present.rotationFor(sensor, ecra)
            assertEquals(Present.exifOrientation(r), Present.exifOrientation(r))
            assertTrue(Present.exifOrientation(r) in intArrayOf(1, 3, 6, 8))
        }
        // Em retrato, o caso normal deste telefone.
        assertEquals(6, Present.exifOrientation(Present.rotationFor(90, 0)))
    }

    // -----------------------------------------------------------------------------------------
    // Enquadramento
    // -----------------------------------------------------------------------------------------

    /**
     * O caso real: uma imagem deitada, rodada 90°, num ecrã em retrato.
     *
     * Depois de rodar, a imagem apresentada é 1530×2040, aspecto 0,75; o ecrã é 1080×2196, aspecto
     * 0,49. A imagem é **mais larga** do que o ecrã, portanto encosta aos lados e sobram barras acima e
     * abaixo.
     */
    @Test
    fun aLandscapeImageRotatedIntoPortraitTouchesTheSides() {
        val f = Present.fit(ecraLargura, ecraAltura, largura, altura, 90)
        assertEquals("devia encostar aos lados", 1f, f[0], 1e-6f)
        assertTrue("devia sobrar espaço em cima e em baixo", f[1] < 1f)
        assertEquals("sem desvio horizontal", 0f, f[2], 1e-6f)
        assertTrue("o desvio vertical devia centrar", f[3] > 0f)
    }

    /** O que sobra é sempre repartido pelos dois lados: a imagem fica centrada. */
    @Test
    fun whatIsLeftOverIsSplitEvenly() {
        for (rotacao in intArrayOf(0, 90, 180, 270)) {
            val f = Present.fit(ecraLargura, ecraAltura, largura, altura, rotacao)
            assertEquals("rotação $rotacao: desvio X", (1f - f[0]) / 2, f[2], 1e-6f)
            assertEquals("rotação $rotacao: desvio Y", (1f - f[1]) / 2, f[3], 1e-6f)
        }
    }

    /**
     * **Nunca se corta.** A escala é sempre 1 ou menos nos dois eixos, o que garante que a imagem cabe
     * inteira e que se vê todo o quadro que vai ser gravado.
     */
    @Test
    fun theWholeFrameIsAlwaysVisible() {
        val ecras = arrayOf(
            intArrayOf(1080, 2196), intArrayOf(2196, 1080),
            intArrayOf(1000, 1000), intArrayOf(400, 3000))
        for (ecra in ecras) {
            for (rotacao in intArrayOf(0, 90, 180, 270)) {
                val f = Present.fit(ecra[0], ecra[1], largura, altura, rotacao)
                assertTrue("${ecra[0]}x${ecra[1]} rot $rotacao: escala X ${f[0]}", f[0] <= 1f + 1e-6f)
                assertTrue("${ecra[0]}x${ecra[1]} rot $rotacao: escala Y ${f[1]}", f[1] <= 1f + 1e-6f)
                assertTrue("um dos eixos devia encostar",
                    Math.abs(f[0] - 1f) < 1e-6f || Math.abs(f[1] - 1f) < 1e-6f)
            }
        }
    }

    /**
     * O aspecto preserva-se: nada de esticar.
     *
     * A imagem ocupa `escalaX * ecraLargura` por `escalaY * ecraAltura` píxeis, e a razão entre esses
     * dois tem de ser o aspecto da imagem apresentada.
     */
    @Test
    fun theAspectRatioIsPreserved() {
        for (rotacao in intArrayOf(0, 90, 180, 270)) {
            val f = Present.fit(ecraLargura, ecraAltura, largura, altura, rotacao)
            val deitada = rotacao % 180 == 0
            val esperado = if (deitada) {
                largura.toDouble() / altura
            } else {
                altura.toDouble() / largura
            }
            val obtido = (f[0] * ecraLargura).toDouble() / (f[1] * ecraAltura)
            assertEquals("rotação $rotacao", esperado, obtido, 1e-4)
        }
    }

    /** Rodar 180° não muda o enquadramento: só o sentido. */
    @Test
    fun oppositeRotationsFrameTheSame() {
        val a = Present.fit(ecraLargura, ecraAltura, largura, altura, 0)
        val b = Present.fit(ecraLargura, ecraAltura, largura, altura, 180)
        for (i in 0 until 4) assertEquals(a[i], b[i], 1e-6f)

        val c = Present.fit(ecraLargura, ecraAltura, largura, altura, 90)
        val d = Present.fit(ecraLargura, ecraAltura, largura, altura, 270)
        for (i in 0 until 4) assertEquals(c[i], d[i], 1e-6f)
    }

    /** Dimensões impossíveis não rebentam nem inventam: devolvem o enquadramento neutro. */
    @Test
    fun degenerateSizesGiveTheNeutralFraming() {
        for (f in listOf(
            Present.fit(0, 100, largura, altura, 90),
            Present.fit(100, 0, largura, altura, 90),
            Present.fit(100, 100, 0, altura, 90),
            Present.fit(100, 100, largura, 0, 90))
        ) {
            assertEquals(1f, f[0], 1e-6f)
            assertEquals(1f, f[1], 1e-6f)
            assertEquals(0f, f[2], 1e-6f)
            assertEquals(0f, f[3], 1e-6f)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Coerência com o shader
    // -----------------------------------------------------------------------------------------

    /**
     * O shader da apresentação tem de aplicar a rotação **inversa** aos pontos de destino.
     *
     * Para rodar a imagem 90° no sentido dos ponteiros, o ponto de destino (x,y) vem da origem
     * (y, 1−x). Trocar isto dá uma imagem rodada para o lado errado, que se vê — mas o par 90/270
     * trocado entre si é mais difícil de notar num visor ao vivo.
     */
    @Test
    fun theShaderAppliesTheInverseRotation() {
        val fonte = GlslSource.PRESENT_FRAGMENT
        assertTrue("falta a rotação de 90", fonte.contains("s = vec2(d.y, 1.0 - d.x)"))
        assertTrue("falta a rotação de 270", fonte.contains("s = vec2(1.0 - d.y, d.x)"))
        assertTrue("falta a rotação de 180", fonte.contains("s = vec2(1.0 - d.x, 1.0 - d.y)"))
    }

    /** Fora do enquadramento é preto: são as barras que garantem que se vê o quadro todo. */
    @Test
    fun outsideTheFramingTheShaderPaintsBlack() {
        val fonte = GlslSource.PRESENT_FRAGMENT
        assertTrue("falta o corte", fonte.contains("d.x < 0.0 || d.x > 1.0"))
        assertTrue("falta o preto", fonte.contains("vec4(0.0, 0.0, 0.0, 1.0)"))
    }

    /**
     * O y do `gl_FragCoord` cresce para cima e o da imagem para baixo. Sem a inversão, o visor sairia
     * espelhado na vertical — e como a imagem também roda, isso não é óbvio de ver.
     */
    @Test
    fun theShaderFlipsYIntoScreenCoordinates() {
        assertTrue(GlslSource.PRESENT_FRAGMENT.contains("1.0 - gl_FragCoord.y / float(uAlvo.y)"))
    }

    @Test
    fun everyPresentUniformExistsInTheShader() {
        for (nome in GlslSource.PRESENT_UNIFORM_NAMES) {
            assertTrue("o shader não declara $nome",
                Regex("uniform\\s+\\w+\\s+" + nome + "\\s*;").containsMatchIn(
                    GlslSource.PRESENT_FRAGMENT))
        }
        val declarados = Regex("uniform\\s+\\w+\\s+(\\w+)\\s*;")
            .findAll(GlslSource.PRESENT_FRAGMENT).map { it.groupValues[1] }.toList()
        for (nome in declarados) {
            assertTrue("o shader declara $nome mas ninguém lho envia",
                GlslSource.PRESENT_UNIFORM_NAMES.contains(nome))
        }
    }
}
