package st.btu.trading.pillar.models.domain

import org.specs2.mutable.Specification

class OperationTypeSpec extends Specification {

  "OperationType" >> {
    "decode" >> {
      "decodes a known raw value to its named case" >> {
        OperationType.decode("OPERATION_TYPE_BUY") must_== OperationType.Buy
      }

      "falls back to Unknown(raw), round-tripping the raw value through .value" >> {
        val decoded = OperationType.decode("OPERATION_TYPE_SOME_FUTURE_TYPE")
        decoded must_== OperationType.Unknown("OPERATION_TYPE_SOME_FUTURE_TYPE")
        decoded.value must_== "OPERATION_TYPE_SOME_FUTURE_TYPE"
      }
    }

    "isBuy" >> {
      "is true for every buy-family case" >> {
        List(
          OperationType.Buy,
          OperationType.BuyCard,
          OperationType.BuyMargin,
          OperationType.DeliveryBuy
        )
          .forall(_.isBuy) must beTrue
      }

      "is false for a sell, a transfer-in and an unknown value" >> {
        OperationType.Sell.isBuy must beFalse
        OperationType.InputSecurities.isBuy must beFalse
        OperationType.Unknown("x").isBuy must beFalse
      }
    }

    "isSell" >> {
      "is true for every sell-family case, including a bond's full repayment" >> {
        List(
          OperationType.Sell,
          OperationType.SellCard,
          OperationType.SellMargin,
          OperationType.DeliverySell,
          OperationType.BondRepaymentFull
        ).forall(_.isSell) must beTrue
      }

      "is false for a buy and a transfer-out" >> {
        OperationType.Buy.isSell must beFalse
        OperationType.OutputSecurities.isSell must beFalse
      }
    }

    "isExternalFlow" >> {
      "is true only for Input and Output" >> {
        OperationType.Input.isExternalFlow must beTrue
        OperationType.Output.isExternalFlow must beTrue
        OperationType.Buy.isExternalFlow must beFalse
      }
    }

    "isDividendOrCoupon" >> {
      "is true only for Dividend and Coupon" >> {
        OperationType.Dividend.isDividendOrCoupon must beTrue
        OperationType.Coupon.isDividendOrCoupon must beTrue
        OperationType.DividendTax.isDividendOrCoupon must beFalse
      }
    }

    "isWithheldTax" >> {
      "is true for the tax and tax-repo family, including every progressive variant" >> {
        List(
          OperationType.BondTax,
          OperationType.DividendTax,
          OperationType.Tax,
          OperationType.TaxCorrection,
          OperationType.BenefitTax,
          OperationType.TaxProgressive,
          OperationType.BondTaxProgressive,
          OperationType.DividendTaxProgressive,
          OperationType.BenefitTaxProgressive,
          OperationType.TaxCorrectionProgressive,
          OperationType.TaxRepo,
          OperationType.TaxRepoProgressive,
          OperationType.TaxRepoHold,
          OperationType.TaxRepoRefund,
          OperationType.TaxRepoHoldProgressive,
          OperationType.TaxRepoRefundProgressive
        ).forall(_.isWithheldTax) must beTrue
      }

      "is false for Dividend itself and for an unrelated case" >> {
        OperationType.Dividend.isWithheldTax must beFalse
        OperationType.Buy.isWithheldTax must beFalse
      }
    }
  }

  "OperationStatus" >> {
    "decode" >> {
      "decodes the known Executed value" >> {
        OperationStatus.decode("OPERATION_STATE_EXECUTED") must_== OperationStatus.Executed
      }

      "falls back to Unknown(raw) for anything else" >> {
        OperationStatus.decode("OPERATION_STATE_CANCELED") must_==
          OperationStatus.Unknown("OPERATION_STATE_CANCELED")
      }
    }
  }
}
