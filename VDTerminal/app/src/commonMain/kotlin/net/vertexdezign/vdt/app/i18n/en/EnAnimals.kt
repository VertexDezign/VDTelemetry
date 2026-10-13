package net.vertexdezign.vdt.app.i18n.en

import net.vertexdezign.vdt.app.i18n.AnimalsStrings

internal object EnAnimals : AnimalsStrings {
  override val waiting = "Waiting for animal data…"
  override val empty = "No owned animals"
  override val listTitle = "Pens"
  override val selectPrompt = "Select a pen"
  override fun penSubtitle(count: Int, max: Int): String = "$count / $max animals"

  override val productivity = "Productivity"
  override val animals = "Animals"
  override val food = "Food"
  override val conditions = "Conditions"

  override val columnAnimal = "Animal"
  override val columnCount = "Count"
  override val columnAge = "Age"
  override val columnHealth = "Health"
  override val columnRepro = "Repro"
}
