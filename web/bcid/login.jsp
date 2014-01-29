<%@ include file="header.jsp" %>

<div class="section">
    <div class="sectioncontent" id="login">
        <h2>BCID Login</h2>

        <form method="POST" action="/id/loginService">
            <table>
                <tr>
                    <td align="right">Username</td>
                    <td><input type="text" name="username"></td>
                </tr>
                <tr>
                    <td align="right">Password</td>
                    <td><input type="password" name="password"></td>
                </tr>
                <c:if test="${param['error'] != null}">
                <tr></tr>
                <tr>
                    <td></td>
                    <td class="error" align="center">Bad Credentials</td>
                </tr>
                </c:if>
                <tr>
                    <td></td>
                    <td ><input type="submit" value="Submit"></td>
                </tr>
            </table>
        </form>

    </div>
</div>