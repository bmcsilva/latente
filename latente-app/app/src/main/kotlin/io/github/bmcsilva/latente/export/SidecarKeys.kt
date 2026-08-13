package io.github.bmcsilva.latente.export

/**
 * Os nomes dos campos do sidecar que alguém volta a **ler**.
 *
 * Num sítio só porque são um contrato entre o `Sidecar`, que os escreve, e o `SidecarRead`, que os lê
 * para reconstruir a revelação. Com o nome escrito à letra nas duas pontas, renomear um campo compila
 * e passa nos testes — e o que acontece é o negativo revelar com as omissões em vez da receita, sem
 * nada a assinalar. Assim, renomear um campo obriga as duas pontas a mudar juntas.
 *
 * Só estão aqui os campos que se lêem de volta. Os outros são para olhos humanos: mudar-lhes o nome
 * não quebra nada, e enchê-los aqui só dava a ilusão de contrato onde ele não existe.
 */
object SidecarKeys {
    const val CAMERA_ID = "id da câmara"
    const val ROTATION_DEGREES = "rotação do ficheiro graus"
    const val DEVELOP_EV = "exposição de revelação EV"
    const val KELVIN = "temperatura K"
    const val SHADING_STRENGTH = "força da vinhetagem"
    const val ROLLOFF = "rolloff"
}
