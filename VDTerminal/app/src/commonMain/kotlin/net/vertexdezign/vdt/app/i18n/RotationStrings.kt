package net.vertexdezign.vdt.app.i18n

/** The Crop Rotation app (FS25_CropRotation). */
interface RotationStrings {
  val notInstalled: String
  val noPlans: String
  val plans: String
  val newRotation: String
  val noPlansYet: String
  val deleteTitle: String
  val unnamed: String
  val deleteRotation: String
  val noCrops: String
  val addSlot: String
  val removeSlot: String
  val fallow: String
  fun crop(state: Int): String
  val noCatchCrop: String
  val change: String
}
