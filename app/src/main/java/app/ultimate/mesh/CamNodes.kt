package app.ultimate.mesh

/**
 * The owner's own devices that are sharing a camera through Octopus Mesh, and the switch to start or stop watching one. Octopus
 * Mesh registers these when it exists (Ultimate+ only); the phone's web server reads them so the Cameras page can list the same
 * devices as tappable nodes. Without the mesh the list is empty.
 */
object CamNodes {
    data class Node(val id: String, val name: String, val viewing: Boolean)

    @Volatile var list: () -> List<Node> = { emptyList() }
    @Volatile var watch: (id: String, on: Boolean) -> Boolean = { _, _ -> false }
}
