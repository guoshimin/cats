package cats
package arrow

import cats.data.Coproduct

import cats.macros.MacroCompat

/**
  * `FunctionK[F[_], G[_]]` is a functor transformation from `F` to `G`
  * in the same manner that function `A => B` is a morphism from values
  * of type `A` to `B`.
  */
trait FunctionK[F[_], G[_]] extends Serializable { self =>

  /**
    * Applies this functor transformation from `F` to `G`
    */
  def apply[A](fa: F[A]): G[A]

  /**
    * Composes two instances of FunctionK into a new FunctionK with this
    * transformation applied last.
    */
  def compose[E[_]](f: FunctionK[E, F]): FunctionK[E, G] =
    new FunctionK[E, G] {
      def apply[A](fa: E[A]): G[A] = self.apply(f(fa))
    }

  /**
    * Composes two instances of FunctionK into a new FunctionK with this
    * transformation applied first.
    */
  def andThen[H[_]](f: FunctionK[G, H]): FunctionK[F, H] =
    f.compose(self)

  /**
    * Composes two instances of FunctionK into a new FunctionK that transforms
    * a [[cats.data.Coproduct]] to a single functor.
    *
    * This transformation will be used to transform left `F` values while
    * `h` will be used to transform right `H` values.
    */
  def or[H[_]](h: FunctionK[H, G]): FunctionK[Coproduct[F, H, ?], G] =
    new FunctionK[Coproduct[F, H, ?], G] {
      def apply[A](fa: Coproduct[F, H, A]): G[A] = fa.run match {
        case Left(ff) => self(ff)
        case Right(gg) => h(gg)
      }
    }
}

object FunctionK {

  /**
    * The identity transformation of `F` to `F`
    */
  def id[F[_]]: FunctionK[F, F] =
    new FunctionK[F, F] {
      def apply[A](fa: F[A]): F[A] = fa
    }

  /**
    * Lifts function `f` of `F[A] => G[A]` into a `FunctionK[F, G]`.
    *
    * {{{
    *   def headOption[A](list: List[A]): Option[A] = list.headOption
    *   val lifted: FunctionK[List, Option] = FunctionK.lift(headOption)
    * }}}
    *
    * Note: This method has a macro implementation that returns a new
    * `FunctionK` instance as follows:
    *
    * {{{
    *   new FunctionK[F, G] {
    *     def apply[A](fa: F[A]): G[A] = f(fa)
    *   }
    * }}}
    *
    * Additionally, the type parameters on `f` must not be specified.
    */
  def lift[F[_], G[_]](f: (F[α] ⇒ G[α]) forSome { type α }): FunctionK[F, G] =
    macro FunctionKMacros.lift[F, G]

}

private[arrow] object FunctionKMacros extends MacroCompat {

  def lift[F[_], G[_]](c: Context)(
    f: c.Expr[(F[α] ⇒ G[α]) forSome { type α }]
  )(
    implicit evF: c.WeakTypeTag[F[_]], evG: c.WeakTypeTag[G[_]]
  ): c.Expr[FunctionK[F, G]] =
    c.Expr[FunctionK[F, G]](new Lifter[c.type ](c).lift[F, G](f.tree))
    // ^^note: extra space after c.type to appease scalastyle

  private[this] class Lifter[C <: Context](val c: C) {
    import c.universe._

    def lift[F[_], G[_]](tree: Tree)(
      implicit evF: c.WeakTypeTag[F[_]], evG: c.WeakTypeTag[G[_]]
    ): Tree = unblock(tree) match {
      case q"($param) => $trans[..$typeArgs](${ arg: Ident })" if param.name == arg.name ⇒

        typeArgs
          .collect { case tt: TypeTree => tt }
          .find(tt => Option(tt.original).isDefined)
          .foreach { param => c.abort(param.pos,
            s"type parameter $param must not be supplied when lifting function $trans to FunctionK")
        }

        val F = punchHole(evF.tpe)
        val G = punchHole(evG.tpe)

        q"""
        new FunctionK[$F, $G] {
          def apply[A](fa: $F[A]): $G[A] = $trans(fa)
        }
       """
      case other ⇒
        c.abort(other.pos, s"Unexpected tree $other when lifting to FunctionK")
    }

    private[this] def unblock(tree: Tree): Tree = tree match {
      case Block(Nil, expr) ⇒ expr
      case _                ⇒ tree
    }

    private[this] def punchHole(tpe: Type): Tree = tpe match {
      case PolyType(undet :: Nil, underlying: TypeRef) ⇒
        val α = compatNewTypeName(c, "α")
        def rebind(typeRef: TypeRef): Tree =
          if (typeRef.sym == undet) tq"$α"
          else {
            val args = typeRef.args.map {
              case ref: TypeRef => rebind(ref)
              case arg => tq"$arg"
            }
            tq"${typeRef.sym}[..$args]"
          }
        val rebound = rebind(underlying)
        tq"""({type λ[$α] = $rebound})#λ"""
      case TypeRef(pre, sym, Nil) ⇒
        tq"$sym"
      case _ =>
        c.abort(c.enclosingPosition, s"Unexpected type $tpe when lifting to FunctionK")
    }

  }

}
