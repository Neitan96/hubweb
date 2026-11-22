package br.com.hubweb

import android.content.Context
import java.security.MessageDigest

fun sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("hubweb", Context.MODE_PRIVATE)

    init {
        // Build da empresa: na primeira abertura grava a URL e o token embutidos nas Configurações, como se tivessem sido
        // digitados. Assim eles continuam valendo quando o app for atualizado pelo GitHub (o APK público não os traz).
        // Vazio no build público. Só acontece uma vez: depois, o que está nas Configurações (ou veio da lista) é que vale.
        if ((BuildConfig.EMPRESA_URL.isNotEmpty() || BuildConfig.EMPRESA_TOKEN.isNotEmpty()) && !sp.getBoolean("empresaSemeada", false)) {
            val ed = sp.edit().putBoolean("empresaSemeada", true)
            if (sp.getString("configUrl", "").isNullOrEmpty()) ed.putString("configUrl", BuildConfig.EMPRESA_URL)
            if (sp.getString("token", "").isNullOrEmpty()) ed.putString("token", BuildConfig.EMPRESA_TOKEN)
            ed.apply()
        }
    }

    /** URL da lista: a do MDM (configuração gerenciada), quando houver; senão a das Configurações (digitada, ou trocada pela lista). */
    var configUrl: String
        get() = managedUrl.ifEmpty { sp.getString("configUrl", "") ?: "" }
        set(v) = sp.edit().putString("configUrl", v.trim()).apply()

    var token: String
        get() = managedToken.ifEmpty { sp.getString("token", "") ?: "" }
        set(v) = sp.edit().putString("token", v.trim()).apply()

    /** O que o MDM definiu (ManagedConfig); vazio = não definido. Quando há valor, ele manda e a tela o trava. */
    var managedUrl: String
        get() = sp.getString("managedUrl", "") ?: ""
        set(v) = sp.edit().putString("managedUrl", v).apply()

    var managedToken: String
        get() = sp.getString("managedToken", "") ?: ""
        set(v) = sp.edit().putString("managedToken", v).apply()

    val urlGerenciada: Boolean get() = managedUrl.isNotEmpty()
    val tokenGerenciado: Boolean get() = managedToken.isNotEmpty()

    /** Minutos sem toque/tecla até voltar ao launcher; 0 desativa. Vem da lista (ConfigRepo.aplicarLimites). */
    var timeoutMin: Int
        get() = sp.getInt("timeoutMin", 30)
        set(v) = sp.edit().putInt("timeoutMin", v).apply()

    /** Sistemas mantidos abertos, conforme a RAM e a lista (ConfigRepo.aplicarLimites). */
    var maxAlive: Int
        get() = sp.getInt("maxAlive", 2)
        set(v) = sp.edit().putInt("maxAlive", v).apply()

    /** Escolha feita no próprio app; só vale quando a lista não define `modoKiosk`. */
    var kioskLocal: Boolean
        get() = sp.getBoolean("kiosk", false)
        set(v) = sp.edit().putBoolean("kiosk", v).apply()

    /** `modoKiosk` da última lista recebida: 1 = ligado, 0 = desligado, -1 = a lista não define. */
    var kioskRemote: Int
        get() = sp.getInt("kioskRemote", -1)
        set(v) = sp.edit().putInt("kioskRemote", v).apply()

    /** Valor efetivo: a lista, quando define, manda; senão vale a configuração do app. */
    val kiosk: Boolean get() = if (kioskRemote >= 0) kioskRemote == 1 else kioskLocal

    var bootCount: Int
        get() = sp.getInt("bootCount", -2)
        set(v) = sp.edit().putInt("bootCount", v).apply()

    var bootTime: Long
        get() = sp.getLong("bootTime", 0)
        set(v) = sp.edit().putLong("bootTime", v).apply()

    /** Botão flutuante da calculadora: escolha do usuário (não exige senha). Desligado por padrão. */
    var calcButton: Boolean
        get() = sp.getBoolean("calcButton", false)
        set(v) = sp.edit().putBoolean("calcButton", v).apply()

    /** Botões flutuantes da lista (não obrigatórios) que o usuário ligou, por id do item. */
    var fabsOn: Set<String>
        get() = sp.getStringSet("fabsOn", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("fabsOn", HashSet(v)).apply()

    /** O usuário travou a posição dos botões flutuantes (não dá para arrastar; ficam onde estão). */
    var fabsLocked: Boolean
        get() = sp.getBoolean("fabsLocked", false)
        set(v) = sp.edit().putBoolean("fabsLocked", v).apply()

    /** Última posição (px) de um botão flutuante; null = ainda não movido. */
    fun fabPos(key: String): Pair<Float, Float>? {
        val p = sp.getString("fab_$key", null)?.split(",") ?: return null
        val x = p.getOrNull(0)?.toFloatOrNull() ?: return null
        val y = p.getOrNull(1)?.toFloatOrNull() ?: return null
        return x to y
    }

    fun setFabPos(key: String, x: Float, y: Float) = sp.edit().putString("fab_$key", "$x,$y").apply()

    /** URL da imagem de fundo que está salva em disco (filesDir/fundo.img). */
    var fundoUrl: String
        get() = sp.getString("fundoUrl", "") ?: ""
        set(v) = sp.edit().putString("fundoUrl", v).apply()

    /**
     * SHA-256 (hex) da senha de administração, vinda do JSON da lista. Fica salvo para valer offline.
     * Vazio = a lista ainda não trouxe senha (as configurações ficam abertas para o primeiro setup).
     */
    var passSha256: String
        get() = sp.getString("passSha256", "") ?: ""
        set(v) = sp.edit().putString("passSha256", v).apply()

    /** URL da logo do cabeçalho que está salva em disco (filesDir/logo.img). */
    var logoUrl: String
        get() = sp.getString("logoUrl", "") ?: ""
        set(v) = sp.edit().putString("logoUrl", v).apply()

    /** Botões flutuantes da lista que já apareceram alguma vez (o estado inicial só vale na primeira vez). */
    var fabsSeen: Set<String>
        get() = sp.getStringSet("fabsSeen", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("fabsSeen", HashSet(v)).apply()

    /** O `calculadoraInicial` da lista já foi aplicado (só vale na primeira vez que a lista o traz). */
    var calcInitialApplied: Boolean
        get() = sp.getBoolean("calcInitialApplied", false)
        set(v) = sp.edit().putBoolean("calcInitialApplied", v).apply()

    /**
     * Horário (relógio do aparelho, ms) da última tentativa de buscar a lista e as dicas. Fica salvo, então fechar e
     * abrir o app não zera o intervalo mínimo.
     */
    var listaTentativaEm: Long
        get() = sp.getLong("listaTentativaEm", 0)
        set(v) = sp.edit().putLong("listaTentativaEm", v).apply()

    var dicasTentativaEm: Long
        get() = sp.getLong("dicasTentativaEm", 0)
        set(v) = sp.edit().putLong("dicasTentativaEm", v).apply()

    /** Último endereço de dicas tentado e o último baixado com sucesso (endereço novo = baixa já). */
    var dicasUrlTentada: String
        get() = sp.getString("dicasUrlTentada", "") ?: ""
        set(v) = sp.edit().putString("dicasUrlTentada", v).apply()

    var dicasUrlBaixada: String
        get() = sp.getString("dicasUrlBaixada", "") ?: ""
        set(v) = sp.edit().putString("dicasUrlBaixada", v).apply()

    /** Última consulta (tentativa) ao GitHub por versão nova do app, a última que deu certo e a versão nova achada (JSON). */
    var appTentativaEm: Long
        get() = sp.getLong("appTentativaEm", 0)
        set(v) = sp.edit().putLong("appTentativaEm", v).apply()

    var appVerificadoEm: Long
        get() = sp.getLong("appVerificadoEm", 0)
        set(v) = sp.edit().putLong("appVerificadoEm", v).apply()

    var appNovaJson: String
        get() = sp.getString("appNovaJson", "") ?: ""
        set(v) = sp.edit().putString("appNovaJson", v).apply()

    /** Usuários identificados por sistema (JSON); vale até o aparelho reiniciar, como os cookies. */
    var usersJson: String
        get() = sp.getString("usersJson", "") ?: ""
        set(v) = sp.edit().putString("usersJson", v).apply()

    /** Já pediu as permissões na primeira abertura (não repete a cada início; depois, só sob demanda). */
    var permsAsked: Boolean
        get() = sp.getBoolean("permsAsked", false)
        set(v) = sp.edit().putBoolean("permsAsked", v).apply()

    val hasPassword: Boolean get() = passSha256.isNotEmpty()

    /** Retorna null se a senha confere; senão a mensagem de erro. 5 erros bloqueiam por 5 min. */
    fun verifyPassword(pw: String): String? {
        if (!hasPassword) return null
        val now = System.currentTimeMillis()
        val lock = sp.getLong("lockUntil", 0)
        if (now < lock) return "Muitas tentativas. Aguarde ${(lock - now) / 60_000 + 1} min."
        if (MessageDigest.isEqual(sha256Hex(pw).toByteArray(), passSha256.toByteArray())) {
            sp.edit().putInt("failCount", 0).apply()
            return null
        }
        val fails = sp.getInt("failCount", 0) + 1
        if (fails >= 5) {
            sp.edit().putInt("failCount", 0).putLong("lockUntil", now + 5 * 60_000).apply()
            return "Senha incorreta. Bloqueado por 5 min."
        }
        sp.edit().putInt("failCount", fails).apply()
        return "Senha incorreta ($fails/5)."
    }
}
