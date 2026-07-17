package net.vertexdezign.vdt.app

/** What the shell is currently showing: a full-screen [VdtApp][net.vertexdezign.vdt.app.apps.VdtApp]
 *  or a customizable [Page][net.vertexdezign.vdt.app.pages.Page]. */
sealed interface Screen {
  data class OpenApp(val appId: String) : Screen

  data class OpenPage(val pageId: String) : Screen
}
