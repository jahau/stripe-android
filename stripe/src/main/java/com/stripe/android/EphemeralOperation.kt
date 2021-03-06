package com.stripe.android

import android.os.Parcelable
import com.stripe.android.model.PaymentMethod
import com.stripe.android.model.ShippingInformation
import com.stripe.android.model.Source
import kotlinx.android.parcel.Parcelize

internal sealed class EphemeralOperation : Parcelable {
    internal abstract val id: String

    @Parcelize
    internal data class RetrieveKey(
        override val id: String
    ) : EphemeralOperation()

    internal sealed class Customer : EphemeralOperation() {
        @Parcelize
        data class AddSource(
            val sourceId: String,
            @Source.SourceType val sourceType: String,
            override val id: String
        ) : Customer()

        @Parcelize
        data class DeleteSource(
            val sourceId: String,
            override val id: String
        ) : Customer()

        @Parcelize
        data class AttachPaymentMethod(
            val paymentMethodId: String,
            override val id: String
        ) : Customer()

        @Parcelize
        data class DetachPaymentMethod(
            val paymentMethodId: String,
            override val id: String
        ) : Customer()

        @Parcelize
        data class PaymentMethods(
            internal val type: PaymentMethod.Type,
            override val id: String
        ) : Customer()

        @Parcelize
        data class UpdateShipping(
            val shippingInformation: ShippingInformation,
            override val id: String
        ) : Customer()

        @Parcelize
        data class UpdateDefaultSource(
            val sourceId: String,
            @Source.SourceType val sourceType: String,
            override val id: String
        ) : Customer()
    }

    internal sealed class Issuing : EphemeralOperation() {
        @Parcelize
        data class RetrievePin(
            val cardId: String,
            val verificationId: String,
            val userOneTimeCode: String,
            override val id: String
        ) : Issuing()

        @Parcelize
        data class UpdatePin(
            val cardId: String,
            val newPin: String,
            val verificationId: String,
            val userOneTimeCode: String,
            override val id: String
        ) : Issuing()
    }
}
