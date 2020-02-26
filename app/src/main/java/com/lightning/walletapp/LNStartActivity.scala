package com.lightning.walletapp

import spray.json._
import android.view._
import android.widget._
import android.support.v4.app._
import com.lightning.walletapp.ln._
import com.lightning.walletapp.Utils._
import com.lightning.walletapp.ln.wire._
import com.lightning.walletapp.R.string._
import com.lightning.walletapp.ln.Tools._
import com.lightning.walletapp.PayRequest._
import com.github.kevinsawicki.http.HttpRequest._
import com.lightning.walletapp.lnutils.ImplicitConversions._
import com.lightning.walletapp.lnutils.olympus.OlympusWrap._
import com.lightning.walletapp.lnutils.ImplicitJsonFormats._

import fr.acinq.bitcoin.{Bech32, Crypto, MilliSatoshi}
import com.lightning.walletapp.ln.RoutingInfoTag.PaymentRouteVec
import com.lightning.walletapp.Utils.app.TransData.nodeLink
import com.lightning.walletapp.lnutils.JsonHttpUtils.to
import com.lightning.walletapp.helper.ThrottledWork
import fr.acinq.bitcoin.Crypto.PublicKey
import android.graphics.BitmapFactory
import org.bitcoinj.uri.BitcoinURI
import scodec.bits.ByteVector
import android.os.Bundle
import scala.util.Try


class LNStartActivity extends ScanActivity { me =>
  lazy val slidingFragmentAdapter = new FragmentStatePagerAdapter(getSupportFragmentManager) {
    def getItem(currentFragmentPos: Int) = if (0 == currentFragmentPos) new FragLNStart else new FragScan
    def getCount = 2
  }

  override def onBackPressed = {
    val isScannerOpen = 1 == walletPager.getCurrentItem
    if (isScannerOpen) walletPager.setCurrentItem(0, true)
    else super.onBackPressed
  }

  override def onOptionsItemSelected(m: MenuItem) = runAnd(true) {
    if (m.getItemId == R.id.actionScan) walletPager.setCurrentItem(1, true)
  }

  override def onResume = wrap(super.onResume)(walletPager setCurrentItem 0)
  override def onCreateOptionsMenu(menu: Menu) = runAnd(true) {
    // Called after FragLNStart sets its toolbar as actionbar
    getMenuInflater.inflate(R.menu.lnstart, menu)
    FragLNStart.fragment.setupSearch(menu)
  }

  def INIT(s: Bundle) = if (app.isAlive) {
    me setContentView R.layout.activity_pager
    walletPager setAdapter slidingFragmentAdapter
  } else me exitTo classOf[MainActivity]

  def checkTransData =
    app.TransData checkAndMaybeErase {
      case _: LNUrl => me exitTo MainActivity.wallet
      case _: BitcoinURI => me exitTo MainActivity.wallet
      case _: PaymentRequest => me exitTo MainActivity.wallet
      case _: NodeAnnouncement => me goTo classOf[LNStartFundActivity]
      case _ => walletPager.setCurrentItem(0, false)
    }
}

object FragLNStart {
  var fragment: FragLNStart = _
  val defaultHostedNode = HostedChannelRequest(s"03144fcc73cea41a002b2865f98190ab90e4ff58a2ce24d3870f5079081e42922d@5.9.83.143:9735", Some("BLW Den"), "00")
  val bitrefillNa = app.mkNodeAnnouncement(PublicKey.fromValidHex("030c3f19d742ca294a55c00376b3b355c3c90d61c6b6b39554dbc7ac19b141c14f"), NodeAddress.fromParts("52.50.244.44", 9735), "Bitrefill")
  val liteGoNa = app.mkNodeAnnouncement(PublicKey.fromValidHex("029aee02904d4e419770b93c1b07aae2814a79032e23cafb4024cbea6fb71be106"), NodeAddress.fromParts("195.154.169.49", 9735), "LiteGo")
  val acinqNa = app.mkNodeAnnouncement(PublicKey.fromValidHex("03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"), NodeAddress.fromParts("34.239.230.56", 9735), "ACINQ")

  val liteGo = HardcodedNodeView(liteGoNa, "<i>litego.io</i>")
  val acinq = HardcodedNodeView(acinqNa, "<i>strike.acinq.co</i>")
  val bitrefill = HardcodedNodeView(bitrefillNa, "<i>bitrefill.com</i>")
  val recommendedNodes = Vector(defaultHostedNode, acinq, bitrefill, liteGo)
}

class FragLNStart extends Fragment with SearchBar with HumanTimeDisplay { me =>
  lazy val host = me.getActivity.asInstanceOf[LNStartActivity]
  val startNodeText = app getString ln_ops_start_node_view
  var nodes = Vector.empty[StartNodeView]
  FragLNStart.fragment = me

  val worker = new ThrottledWork[String, AnnounceChansNumVec] {
    def work(nodeSearchAsk: String) = LNParams.olympusWrap findNodes nodeSearchAsk
    def error(nodeSearchError: Throwable) = host onFail nodeSearchError

    def process(userQuery: String, results: AnnounceChansNumVec) = {
      val remoteNodeViewWraps = for (remoteNodeInfo <- results) yield RemoteNodeView(remoteNodeInfo)
      nodes = if (userQuery.isEmpty) FragLNStart.recommendedNodes ++ remoteNodeViewWraps else remoteNodeViewWraps
      host.UITask(adapter.notifyDataSetChanged).run
    }
  }

  val adapter = new BaseAdapter {
    def getView(pos: Int, savedView: View, par: ViewGroup) = {
      val slot = host.getLayoutInflater.inflate(R.layout.frag_single_line, null)
      val textLine = slot.findViewById(R.id.textLine).asInstanceOf[TextView]
      textLine setText getItem(pos).asString(startNodeText).html
      slot setBackgroundColor getItem(pos).backgroundColor
      slot
    }

    def getItem(position: Int) = nodes(position)
    def getItemId(position: Int) = position
    def getCount = nodes.size
  }

  def react = worker addWork lastQuery
  def onNodeSelected(pos: Int): Unit = {
    app.TransData.value = adapter getItem pos
    host goTo classOf[LNStartFundActivity]
  }

  override def onCreateView(inf: LayoutInflater, vg: ViewGroup, bn: Bundle) =
    inf.inflate(R.layout.frag_ln_start, vg, false)

  override def onViewCreated(view: View, state: Bundle) = if (app.isAlive) {
    val lnStartNodesList = view.findViewById(R.id.lnStartNodesList).asInstanceOf[ListView]
    me initToolbar view.findViewById(R.id.toolbar).asInstanceOf[android.support.v7.widget.Toolbar]
    wrap(host.getSupportActionBar setTitle action_ln_open)(host.getSupportActionBar setSubtitle ln_status_peer)
    lnStartNodesList setOnItemClickListener host.onTap(onNodeSelected)
    lnStartNodesList setAdapter adapter
    host.checkTransData
    react
  }
}

// DISPLAYING NODES ON UI

sealed trait StartNodeView {
  def asString(base: String): String
  val backgroundColor = 0x00000000
}

case class IncomingChannelParams(nodeView: HardcodedNodeView, open: OpenChannel)
case class HardcodedNodeView(ann: NodeAnnouncement, tip: String) extends StartNodeView {
  // App suggests a bunch of hardcoded and separately fetched nodes with a good liquidity
  def asString(base: String) = base.format(ann.htmlAlias, tip, ann.pretty)
}

case class RemoteNodeView(acn: AnnounceChansNum) extends StartNodeView {
  def asString(base: String) = base.format(chanAnnounce.htmlAlias, chansNumber, chanAnnounce.pretty)
  lazy val chansNumber = app.plur1OrZero(app.getResources getStringArray R.array.ln_ops_start_node_channels, chansNum)
  val chanAnnounce \ chansNum = acn
}

// LNURL response types

object LNUrl {
  def fromBech32(bech32url: String) = {
    val _ \ data = Bech32.decode(bech32url)
    val request = Bech32.five2eight(data)
    LNUrl(Tools bin2readable request)
  }

  def guardResponse(raw: String): String = {
    val validJson = Try(raw.parseJson.asJsObject.fields)
    val hasError = validJson.map(_ apply "reason").map(json2String)
    if (validJson.isFailure) throw new Exception(s"Invalid json from remote provider: $raw")
    if (hasError.isSuccess) throw new Exception(s"Error message from remote provider: ${hasError.get}")
    raw
  }

  def checkHost(host: String) = {
    val uri = android.net.Uri.parse(host)
    val isOnion = host.startsWith("http://") && uri.getHost.endsWith(NodeAddress.onionSuffix)
    val isSSLPlain = host.startsWith("https://") && !uri.getHost.endsWith(NodeAddress.onionSuffix)
    require(isSSLPlain || isOnion, "URI is neither Plain-HTTPS nor Onion-HTTP request")
    uri
  }
}

case class LNUrl(request: String) {
  lazy val k1 = Try(uri getQueryParameter "k1")
  lazy val isAuth = Try(uri getQueryParameter "tag" equals "login").getOrElse(false)
  lazy val isWithdraw = Try(uri getQueryParameter "tag" equals "withdrawRequest").getOrElse(false)
  val uri = LNUrl.checkHost(request)
}

trait LNUrlData {
  def checkAgainstParent(lnUrl: LNUrl): Boolean = true
  def unsafe(req: String) = get(req, false).header("Connection", "close")

  def validate(lnUrl: LNUrl) = checkAgainstParent(lnUrl) match {
    case false => throw new Exception("Callback domain mismatch")
    case true => this
  }
}

object WithdrawRequest {
  def fromURI(uri: android.net.Uri) =
    WithdrawRequest(callback = uri getQueryParameter "callback",
      minWithdrawable = Some(uri.getQueryParameter("minWithdrawable").toLong),
      maxWithdrawable = uri.getQueryParameter("maxWithdrawable").toLong,
      defaultDescription = uri getQueryParameter "defaultDescription",
      k1 = uri getQueryParameter "k1")
}

case class WithdrawRequest(callback: String, k1: String, maxWithdrawable: Long, defaultDescription: String, minWithdrawable: Option[Long] = None) extends LNUrlData {
  def requestWithdraw(lnUrl: LNUrl, pr: PaymentRequest) = unsafe(callbackUri.buildUpon.appendQueryParameter("pr", PaymentRequest write pr).appendQueryParameter("k1", k1).build.toString)
  override def checkAgainstParent(lnUrl: LNUrl) = lnUrl.uri.getHost == callbackUri.getHost

  val callbackUri = LNUrl.checkHost(callback)
  val minCanReceive = minWithdrawable.getOrElse(LNParams.minPaymentMsat).max(LNParams.minPaymentMsat)
  require(minCanReceive <= maxWithdrawable, s"$maxWithdrawable is less than min $minCanReceive")
}

case class IncomingChannelRequest(uri: String, callback: String, k1: String) extends LNUrlData {
  override def checkAgainstParent(lnUrl: LNUrl) = lnUrl.uri.getHost == callbackUri.getHost

  val nodeLink(nodeKey, hostAddress, portNumber) = uri
  val pubKey = PublicKey(ByteVector fromValidHex nodeKey)
  val address = NodeAddress.fromParts(hostAddress, portNumber.toInt)
  val ann = app.mkNodeAnnouncement(pubKey, address, alias = hostAddress)
  val callbackUri = LNUrl.checkHost(callback)

  def requestChannel =
    unsafe(callbackUri.buildUpon.appendQueryParameter("private", "1").appendQueryParameter("k1", k1)
      .appendQueryParameter("remoteid", LNParams.keys.extendedNodeKey.publicKey.toString).build.toString)
}

case class HostedChannelRequest(uri: String, alias: Option[String], k1: String) extends LNUrlData with StartNodeView {
  def asString(base: String) = base.format(ann.htmlAlias, app getString ln_ops_start_fund_hosted_channel, ann.pretty)
  override val backgroundColor = Denomination.yellowHighlight

  val secret = ByteVector fromValidHex k1
  val nodeLink(nodeKey, hostAddress, portNumber) = uri
  val pubKey = PublicKey(ByteVector fromValidHex nodeKey)
  val address = NodeAddress.fromParts(hostAddress, portNumber.toInt)
  val ann = app.mkNodeAnnouncement(pubKey, address, alias getOrElse hostAddress)
}

object PayRequest {
  type TagAndContent = Vector[String]
  type PayMetaData = Vector[TagAndContent]
  type KeyAndUpdate = (PublicKey, ChannelUpdate)
  type Route = Vector[KeyAndUpdate]
}

case class PayLinkInfo(image64: String, lnurl: LNUrl, text: String, lastMsat: MilliSatoshi, lastDate: Long) {
  lazy val bitmap = for (imageBytes <- imageBytesTry) yield BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length)
  def imageBytesTry = Try(org.spongycastle.util.encoders.Base64 decode image64)
}

case class PayRequest(callback: String, maxSendable: Long, minSendable: Long, metadata: String) extends LNUrlData {
  private val decodedMetadata = to[PayMetaData](metadata)

  val metaDataImageBase64s = for {
    Vector("image/png;base64" | "image/jpeg;base64", content) <- decodedMetadata
    _ = require(content.length <= 136536, s"Image is too heavy, base64 length=${content.length}")
  } yield content

  val callbackUri = LNUrl.checkHost(callback)
  val minCanSend = minSendable max LNParams.minPaymentMsat
  private val metaDataTexts = decodedMetadata.collect { case Vector("text/plain", data) => data }
  require(metaDataTexts.size == 1, "There must be exactly one text/plain entry in metadata")
  require(minCanSend <= maxSendable, s"$maxSendable is less than min $minCanSend")
  val metaDataTextPlain = metaDataTexts.head

  override def checkAgainstParent(lnUrl: LNUrl) = lnUrl.uri.getHost == callbackUri.getHost
  def metaDataHash: ByteVector = Crypto.sha256(ByteVector view metadata.getBytes)

  def requestFinal(amount: MilliSatoshi, fromnodes: String = new String) =
    unsafe(callbackUri.buildUpon.appendQueryParameter("amount", amount.toLong.toString)
      .appendQueryParameter("fromnodes", fromnodes).build.toString)
}

case class PayRequestFinal(successAction: Option[PaymentAction], disposable: Option[Boolean], routes: Vector[Route], pr: String) extends LNUrlData {
  for (route <- routes) for (nodeId \ update <- route) require(Announcements.checkSig(update, nodeId), "Extra route contains an invalid update")
  val extraPaymentRoutes: PaymentRouteVec = for (route <- routes) yield route map { case nodeId \ chanUpdate => chanUpdate toHop nodeId }
  val paymentRequest: PaymentRequest = PaymentRequest.read(pr)
  val isThrowAway = disposable getOrElse true
}