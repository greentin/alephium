package org.alephium.protocol.script

import org.alephium.crypto.{ED25519, ED25519PrivateKey, ED25519PublicKey, ED25519Signature}
import org.alephium.protocol.model.UnsignedTransaction
import org.alephium.serde.Serde
import org.alephium.util.AVector

final case class Witness(privateScript: AVector[Instruction], signatures: AVector[ED25519Signature])

object Witness {
  implicit val serde: Serde[Witness] =
    Serde.forProduct2(Witness(_, _), t => (t.privateScript, t.signatures))

  // TODO: optimize the following scripts using cache
  def build(payTo: PayTo, publicKey: ED25519PublicKey, signature: ED25519Signature): Witness =
    payTo match {
      case PayTo.PKH => p2pkh(publicKey, signature)
      case PayTo.SH  => p2sh(publicKey, signature)
    }

  def build(payTo: PayTo,
            unsignedTransaction: UnsignedTransaction,
            publicKey: ED25519PublicKey,
            privateKey: ED25519PrivateKey): Witness = payTo match {
    case PayTo.PKH => p2pkh(unsignedTransaction, publicKey, privateKey)
    case PayTo.SH  => p2sh(unsignedTransaction, publicKey, privateKey)
  }

  private def p2pkh(unsignedTransaction: UnsignedTransaction,
                    publicKey: ED25519PublicKey,
                    privateKey: ED25519PrivateKey): Witness = {
    val signature = ED25519.sign(unsignedTransaction.hash.bytes, privateKey)
    p2pkh(publicKey, signature)
  }

  private def p2pkh(publicKey: ED25519PublicKey, signature: ED25519Signature): Witness = {
    Witness(PriScript.build(PayTo.PKH, publicKey), AVector(signature))
  }

  private def p2sh(unsignedTransaction: UnsignedTransaction,
                   publicKey: ED25519PublicKey,
                   privateKey: ED25519PrivateKey): Witness = {
    val signature = ED25519.sign(unsignedTransaction.hash.bytes, privateKey)
    p2sh(publicKey, signature)
  }

  private def p2sh(publicKey: ED25519PublicKey, signature: ED25519Signature): Witness = {
    Witness(PriScript.build(PayTo.SH, publicKey), AVector(signature))
  }
}
